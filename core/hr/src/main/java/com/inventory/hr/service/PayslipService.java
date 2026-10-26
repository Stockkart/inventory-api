package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.AmountToWordsConverter;
import com.inventory.documentservice.rest.dto.hr.PayslipDocumentRequest;
import com.inventory.documentservice.service.hr.PayslipPdfService;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.rest.dto.request.EmailPayslipsRequest;
import com.inventory.hr.rest.dto.response.EmailPayslipsResponse;
import com.inventory.hr.rest.dto.response.MyPayslipResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.notifications.domain.model.EmailTemplate;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.service.MessagingService;
import com.inventory.user.service.ShopServiceAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Payslips (R10): rendered on demand from a FINALISED run's line (D10), never stored. Staff need
 * VIEW_SALARIES; self-service finds the caller's employee through {@link SelfServiceResolver} and
 * answers 404 for any line that is not their own. "Email payslips" queues one outbox message per
 * employee with an email; the PDF is rendered when the outbox sends it ({@link
 * #renderAttachment}, decision 1).
 */
@Slf4j
@Service
public class PayslipService {

  /** {@code OutboundMessage.AttachmentRef.kind} of a payslip. */
  public static final String ATTACHMENT_KIND = "PAYSLIP";
  static final int MAX_EMAIL_LINES = 300;
  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter GENERATED =
      DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private PayrollRunRepository runRepository;

  @Autowired private PayrollLineRepository lineRepository;

  @Autowired private HrPermissionGuard guard;

  @Autowired private SelfServiceResolver selfServiceResolver;

  @Autowired private PayslipPdfService payslipPdfService;

  @Autowired(required = false) private ShopServiceAdapter shopServiceAdapter;

  @Autowired private MessagingService messagingService;

  @Autowired private HrQueryRunner queryRunner;

  @Autowired private MetricsWrapper metrics;

  // ---- staff -----------------------------------------------------------------------------------

  /** {@code GET /payroll-runs/{runId}/lines/{lineId}/payslip} (VIEW_SALARIES, FINALISED only). */
  public HrFile staffPayslip(String userId, String shopId, String runId, String lineId) {
    guard.require(userId, shopId, HrPermission.VIEW_SALARIES);
    return queryRunner.read("payslip_pdf", () -> {
      PayrollLine line = lineRepository.findByShopIdAndId(shopId, lineId)
          .filter(l -> runId.equals(l.getRunId()))
          .orElseThrow(() -> new ResourceNotFoundException("PayrollLine", "id", lineId));
      PayrollRun run = runRepository.findByShopIdAndId(shopId, runId)
          .orElseThrow(() -> new ResourceNotFoundException("PayrollRun", "id", runId));
      if (run.getStatus() != PayrollRunStatus.FINALISED) {
        throw new BaseException(ErrorCode.HR_RUN_STATE_CONFLICT,
            "Payslips are ready once the payroll for " + run.getPeriod() + " is finalised");
      }
      return render(shopId, line, "payslip_pdf");
    });
  }

  // ---- self-service ----------------------------------------------------------------------------

  /** {@code GET /me/payslips}: the caller's own lines of FINALISED runs, newest month first. */
  public List<MyPayslipResponse> ownPayslips(String shopId, String userId) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    return queryRunner.read("payslip_list_own", () -> {
      Query lines = Query.query(Criteria.where("shopId").is(shopId).and("employeeId")
          .is(self.getId())).with(Sort.by(Sort.Direction.DESC, "period"));
      lines.fields().include("runId", "period", "fromDate", "toDate", "net", "paymentStatus",
          "paidAt");
      lines.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<PayrollLine> found = mongoTemplate.find(lines, PayrollLine.class);
      Set<String> finalised = finalisedRunIds(shopId,
          found.stream().map(PayrollLine::getRunId).toList());
      List<MyPayslipResponse> out = new ArrayList<>();
      for (PayrollLine l : found) {
        if (finalised.contains(l.getRunId())) {
          PayCycle.Range cycle = PayCycle.stored(l.getPeriod(), l.getFromDate(), l.getToDate());
          out.add(new MyPayslipResponse(l.getId(), l.getRunId(), l.getPeriod(), l.getNet(),
              l.getPaymentStatus() != null ? l.getPaymentStatus() : SalaryPaymentStatus.PENDING,
              l.getPaidAt(), cycle.fromText(), cycle.toText()));
        }
      }
      return out;
    });
  }

  /**
   * {@code GET /me/payslips/{lineId}/pdf}: 404 unless the line is the caller's own and its run
   * is FINALISED (the same answer as an unknown id, so nothing is revealed).
   */
  public HrFile ownPayslip(String shopId, String userId, String lineId) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    return queryRunner.read("payslip_pdf_own", () -> {
      PayrollLine line = lineRepository.findByShopIdAndId(shopId, lineId)
          .filter(l -> self.getId().equals(l.getEmployeeId()))
          .orElseThrow(() -> new ResourceNotFoundException("Payslip", "id", lineId));
      if (finalisedRunIds(shopId, List.of(line.getRunId())).isEmpty()) {
        throw new ResourceNotFoundException("Payslip", "id", lineId);
      }
      return render(shopId, line, "payslip_pdf");
    });
  }

  // ---- email -----------------------------------------------------------------------------------

  /**
   * {@code POST /payroll-runs/{runId}/payslips/email} (RUN_PAYROLL, ≤ 300 lines): one outbox
   * message per employee with an email, holding only a reference to the line (3 reads + one
   * insert). Addresses and amounts are never logged or returned.
   */
  public EmailPayslipsResponse email(String userId, String shopId, String runId,
      EmailPayslipsRequest request) {
    guard.require(userId, shopId, HrPermission.RUN_PAYROLL);
    List<String> ids = lineIds(request);
    return queryRunner.read("payslip_email", () -> {
      PayrollRun run = runRepository.findByShopIdAndId(shopId, runId)
          .orElseThrow(() -> new ResourceNotFoundException("PayrollRun", "id", runId));
      if (run.getStatus() != PayrollRunStatus.FINALISED) {
        throw new BaseException(ErrorCode.HR_RUN_STATE_CONFLICT,
            "Payslips can be emailed once the payroll is finalised");
      }
      Query lines = Query.query(Criteria.where("shopId").is(shopId).and("runId").is(runId)
          .and("_id").in(ids));
      lines.fields().include("employeeId", "period", "fromDate", "toDate", "employeeSnapshot.name",
          "employeeSnapshot.code");
      lines.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<PayrollLine> found = mongoTemplate.find(lines, PayrollLine.class);
      Map<String, String> emails = emails(shopId,
          found.stream().map(PayrollLine::getEmployeeId).toList());
      String shopName = shopName(shopId);
      List<MessagingService.TemplateEmail> outbox = new ArrayList<>();
      EmailPayslipsResponse response = new EmailPayslipsResponse();
      for (PayrollLine l : found) {
        String email = emails.get(l.getEmployeeId());
        PayrollLine.EmployeeSnapshot snap = l.getEmployeeSnapshot();
        if (!StringUtils.hasText(email)) {
          response.getSkippedNoEmail().add(new EmailPayslipsResponse.Skipped(l.getId(),
              l.getEmployeeId(), snap != null ? snap.getCode() : null,
              snap != null ? snap.getName() : null));
          continue;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("employeeName", snap != null && snap.getName() != null ? snap.getName() : "");
        vars.put("shopName", shopName);
        vars.put("periodLabel", periodLabel(l));
        outbox.add(new MessagingService.TemplateEmail(email, vars,
            new OutboundMessage.AttachmentRef(ATTACHMENT_KIND, runId, l.getId(), shopId)));
      }
      response.setQueued(outbox.isEmpty() ? 0
          : messagingService.enqueueTemplateEmails(EmailTemplate.PAYSLIP, outbox));
      log.info("[hr-payslip] run {} of shop {}: {} payslip email(s) queued, {} without email",
          runId, shopId, response.getQueued(), response.getSkippedNoEmail().size());
      return response;
    });
  }

  /**
   * The outbox's send-time render ({@code PayslipAttachmentResolver}): the line's payslip while
   * its run is FINALISED, else null. A null counts as a failed attempt: the outbox retries with
   * its backoff and gives up after {@code MessagingConstants.MAX_RETRIES} (a run reopened for
   * longer than that needs its payslips emailed again after the next finalise).
   */
  public OutboundMessage.Attachment renderAttachment(OutboundMessage.AttachmentRef ref) {
    if (ref == null || !StringUtils.hasText(ref.getShopId()) || !StringUtils.hasText(
        ref.getLineId())) {
      return null;
    }
    PayrollLine line = lineRepository.findByShopIdAndId(ref.getShopId(), ref.getLineId())
        .filter(l -> l.getRunId() != null && l.getRunId().equals(ref.getRunId()))
        .orElse(null);
    if (line == null
        || finalisedRunIds(ref.getShopId(), List.of(line.getRunId())).isEmpty()) {
      return null;
    }
    HrFile file = render(ref.getShopId(), line, "payslip_email_render");
    return new OutboundMessage.Attachment(file.fileName(), file.contentType(),
        Base64.getEncoder().encodeToString(file.bytes()));
  }

  // ---- rendering -------------------------------------------------------------------------------

  private HrFile render(String shopId, PayrollLine line, String operation) {
    long started = System.nanoTime();
    ShopServiceAdapter.ShopSummary shop = shopServiceAdapter != null
        ? shopServiceAdapter.getShopSummary(shopId) : null;
    byte[] pdf = payslipPdfService.generate(toDocument(line, shop, Instant.now()));
    recordDocument(operation, started);
    PayrollLine.EmployeeSnapshot snap = line.getEmployeeSnapshot();
    String code = snap != null && snap.getCode() != null ? snap.getCode() : "employee";
    return new HrFile("payslip-" + safe(code) + "-" + line.getPeriod() + ".pdf", HrFile.PDF, pdf);
  }

  /** The payslip's content (R10.2) from a frozen line: no reads. */
  static PayslipDocumentRequest toDocument(PayrollLine line, ShopServiceAdapter.ShopSummary shop,
      Instant now) {
    PayrollLine.EmployeeSnapshot snap = line.getEmployeeSnapshot() != null
        ? line.getEmployeeSnapshot() : new PayrollLine.EmployeeSnapshot();
    PayslipDocumentRequest doc = new PayslipDocumentRequest();
    doc.setShopName(shop != null && shop.getName() != null ? shop.getName() : "");
    doc.setShopAddress(shop != null ? shop.getAddressLine() : null);
    doc.setEmployeeName(snap.getName());
    doc.setEmployeeCode(snap.getCode());
    doc.setDesignation(StringUtils.hasText(snap.getDesignation()) ? snap.getDesignation() : null);
    doc.setPeriodLabel(periodLabel(line));
    doc.setDayRows(dayRows(line.getDayCounts(), snap.getPayType()));
    List<PayslipDocumentRequest.Item> earnings = new ArrayList<>();
    if (line.getEarnings() != null) {
      line.getEarnings().forEach(e -> earnings.add(
          new PayslipDocumentRequest.Item(e.getLabel(), e.getAmount())));
    }
    List<PayslipDocumentRequest.Item> deductions = new ArrayList<>();
    if (line.getDeductions() != null) {
      line.getDeductions().forEach(d -> deductions.add(
          new PayslipDocumentRequest.Item(d.getLabel(), d.getAmount())));
    }
    doc.setEarnings(earnings);
    doc.setDeductions(deductions);
    doc.setGrossPay(line.getGross());
    doc.setTotalDeductions(line.getTotalDeductions());
    doc.setNetBeforeRounding(line.getNetBeforeRounding());
    doc.setRounding(line.getRounding());
    doc.setNetPay(line.getNet());
    doc.setNetPayInWords(AmountToWordsConverter.convertAmountToWords(
        line.getNet() != null ? line.getNet() : BigDecimal.ZERO));
    BigDecimal owed = line.getAdvanceOutstandingAfter();
    doc.setAdvanceBalance(owed != null && owed.signum() > 0 ? owed : null);
    if (line.getPaymentStatus() == SalaryPaymentStatus.PAID && line.getPaidAt() != null) {
      doc.setPaymentNote("Paid on " + line.getPaidAt()
          + (line.getPaymentMethod() != null ? " (" + methodLabel(line) + ")" : ""));
    }
    doc.setGeneratedAtLabel("Generated " + GENERATED.format(ZonedDateTime.ofInstant(now,
        HrDateUtils.ZONE)));
    return doc;
  }

  /** Paid-days breakdown; rows that are zero are left out except present and paid days. */
  static List<PayslipDocumentRequest.Row> dayRows(PayrollLine.DayCounts c, PayType payType) {
    List<PayslipDocumentRequest.Row> rows = new ArrayList<>();
    if (c == null) {
      return rows;
    }
    addRow(rows, "Days employed", c.getEmployedDays(), true);
    addRow(rows, "Present", c.getPresent(), true);
    addRow(rows, "Half days", c.getHalfDays(), false);
    addRow(rows, "Paid leave", c.getPaidLeave(), false);
    addRow(rows, "Unpaid leave", c.getUnpaidLeave(), false);
    addRow(rows, "Absent", c.getAbsent(), false);
    addRow(rows, "Week off", c.getWeekOff(), false);
    addRow(rows, "Holidays", c.getHoliday(), false);
    addRow(rows, "Worked on a day off", c.getOffDayWorked(), false);
    if (c.getPaidDays() != null) {
      String paid = number(c.getPaidDays());
      boolean monthly = payType == null || payType == PayType.MONTHLY;
      rows.add(new PayslipDocumentRequest.Row("Paid days", monthly && c.getBasisDays() != null
          ? paid + " of " + c.getBasisDays() : paid));
    }
    return rows;
  }

  private static void addRow(List<PayslipDocumentRequest.Row> rows, String label, Object value,
      boolean always) {
    if (value == null) {
      return;
    }
    BigDecimal n = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    if (always || n.signum() != 0) {
      rows.add(new PayslipDocumentRequest.Row(label, number(n)));
    }
  }

  private static String number(BigDecimal n) {
    return n.stripTrailingZeros().toPlainString();
  }

  private static String methodLabel(PayrollLine line) {
    return switch (line.getPaymentMethod()) {
      case CASH -> "Cash";
      case BANK -> "Bank";
      case UPI -> "UPI";
    };
  }

  static String periodLabel(String period) {
    YearMonth month = HrDateUtils.parsePeriod(period);
    return month != null ? MONTH.format(month) : String.valueOf(period);
  }

  /**
   * Label of a salary month: the month name ("April 2025") when it is the calendar month, else
   * its dates ("26 Sep – 25 Oct 2026"), so existing shops (start day 1) see no change.
   */
  static String periodLabel(String period, String fromDate, String toDate) {
    YearMonth month = HrDateUtils.parsePeriod(period);
    if (month == null) {
      return String.valueOf(period);
    }
    PayCycle.Range cycle = PayCycle.stored(period, fromDate, toDate);
    return cycle.equals(PayCycle.range(month, PayCycle.DEFAULT_START_DAY))
        ? MONTH.format(month) : cycle.label();
  }

  static String periodLabel(PayrollLine line) {
    return periodLabel(line.getPeriod(), line.getFromDate(), line.getToDate());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private List<String> lineIds(EmailPayslipsRequest request) {
    Set<String> ids = new LinkedHashSet<>();
    if (request != null && request.getLineIds() != null) {
      for (String id : request.getLineIds()) {
        if (StringUtils.hasText(id)) {
          ids.add(id.trim());
        }
      }
    }
    if (ids.isEmpty()) {
      throw new ValidationException("Choose whose payslips to email");
    }
    if (ids.size() > MAX_EMAIL_LINES) {
      throw new ValidationException("Email at most " + MAX_EMAIL_LINES + " payslips at a time");
    }
    return List.copyOf(ids);
  }

  /** Ids of the given runs that are FINALISED: one read. */
  private Set<String> finalisedRunIds(String shopId, List<String> runIds) {
    Set<String> wanted = new LinkedHashSet<>(runIds);
    wanted.remove(null);
    if (wanted.isEmpty()) {
      return Set.of();
    }
    Query runs = Query.query(Criteria.where("shopId").is(shopId).and("_id").in(wanted)
        .and("status").is(PayrollRunStatus.FINALISED));
    runs.fields().include("_id");
    runs.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    Set<String> out = new LinkedHashSet<>();
    mongoTemplate.find(runs, PayrollRun.class).forEach(r -> out.add(r.getId()));
    return out;
  }

  /** Employee emails by id (email only): one read. */
  private Map<String, String> emails(String shopId, List<String> employeeIds) {
    Map<String, String> out = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return out;
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id")
        .in(new LinkedHashSet<>(employeeIds)));
    query.fields().include("email");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    for (Employee e : mongoTemplate.find(query, Employee.class)) {
      if (StringUtils.hasText(e.getEmail())) {
        out.put(e.getId(), e.getEmail().trim());
      }
    }
    return out;
  }

  private String shopName(String shopId) {
    ShopServiceAdapter.ShopSummary shop = shopServiceAdapter != null
        ? shopServiceAdapter.getShopSummary(shopId) : null;
    return shop != null && shop.getName() != null ? shop.getName() : "your shop";
  }

  /** File names keep letters, digits and dashes only. */
  private static String safe(String value) {
    return value.replaceAll("[^A-Za-z0-9-]", "_");
  }

  private void recordDocument(String operation, long startedNanos) {
    double seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    metrics.increment(HrMetricsConstants.DOCUMENTS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, "operation", operation);
    metrics.increment(HrMetricsConstants.DOCUMENTS_SECONDS_TOTAL, seconds,
        "module", HrMetricsConstants.MODULE, "operation", operation);
  }
}
