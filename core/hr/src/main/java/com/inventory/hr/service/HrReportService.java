package com.inventory.hr.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.mis.MisDocumentKpi;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.mis.MisExcelDocumentService;
import com.inventory.documentservice.service.mis.MisPdfDocumentService;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.rest.dto.response.LeaveBalanceRow;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.service.ShopServiceAdapter;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * HR registers (R5.12, R12.1, R12.2, R12.4, R12.5) through documentservice's neutral MIS
 * workbook / PDF builders; nothing is stored. Each report checks its permission first: the
 * salary register needs VIEW_SALARIES (R12.5), the attendance and leave lists VIEW_ATTENDANCE.
 * The controller methods run under the heavy-request limiter kind {@code HR}.
 */
@Service
public class HrReportService {

  private static final DateTimeFormatter GENERATED =
      DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

  @Autowired private HrPermissionGuard guard;

  @Autowired private AttendanceService attendanceService;

  @Autowired private LeaveBalanceService leaveBalanceService;

  @Autowired private PayrollRunRepository runRepository;

  @Autowired private PayrollLineRepository lineRepository;

  @Autowired private MisExcelDocumentService excelService;

  @Autowired private MisPdfDocumentService pdfService;

  @Autowired(required = false) private ShopServiceAdapter shopServiceAdapter;

  @Autowired private HrQueryRunner queryRunner;

  @Autowired private MetricsWrapper metrics;

  // ---- attendance --------------------------------------------------------------------------------

  /** {@code GET /attendance/month/export?period=} (VIEW_ATTENDANCE): the month register. */
  public HrFile monthExport(String userId, String shopId, String period) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    MonthRegisterResponse register = attendanceService.monthRegister(shopId, period, null);
    MisTabularDocumentRequest doc = base("Attendance register", shopId,
        PayslipService.periodLabel(register.getPeriod()));
    List<String> columns = new ArrayList<>(List.of("Code", "Name"));
    for (String date : register.getDates()) {
      columns.add(date.substring(8));
    }
    columns.addAll(List.of("Present", "Half days", "Absent", "Leave", "Week off", "Holiday",
        "Unmarked", "Late days"));
    doc.setColumns(columns);
    for (MonthRegisterResponse.Row row : register.getRows()) {
      List<String> cells = new ArrayList<>(List.of(text(row.getCode()), text(row.getName())));
      for (MonthRegisterResponse.Cell cell : row.getCells()) {
        cells.add(letter(cell));
      }
      MonthRegisterResponse.Totals t = row.getTotals();
      cells.addAll(List.of(number(t.getPresent()), String.valueOf(t.getHalfDays()),
          number(t.getAbsent()), number(t.getLeave()), number(t.getWeekOff()),
          number(t.getHoliday()), number(t.getUnmarked()), String.valueOf(t.getLateDays())));
      doc.getRows().add(cells);
    }
    doc.setKpis(new ArrayList<>(List.of(
        new MisDocumentKpi("Employees", String.valueOf(register.getRows().size())),
        new MisDocumentKpi("Key", "P present, A absent, H half day, L leave, W week off,"
            + " Ho holiday, - unmarked"))));
    doc.setDetailSheetTitle("Attendance");
    return file("month_xlsx", () -> excelService.generateExcel(doc),
        "attendance-" + register.getPeriod() + ".xlsx", HrFile.XLSX);
  }

  // ---- leave -------------------------------------------------------------------------------------

  /** {@code GET /leave-balances/export?year=} (VIEW_ATTENDANCE, R12.4). */
  public HrFile leaveBalancesExport(String userId, String shopId, String year) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    List<LeaveBalanceRow> rows = queryRunner.read("leave_balances",
        () -> leaveBalanceService.rows(shopId, year, null));
    String leaveYear = rows.isEmpty() ? (year != null ? year : "") : rows.get(0).getLeaveYear();
    MisTabularDocumentRequest doc = base("Leave balances", shopId, "Leave year " + leaveYear);
    // one column group per leave type, in the order of the first row (all rows share it)
    Map<String, String> types = new LinkedHashMap<>();
    rows.stream().findFirst().ifPresent(r -> r.getBalances()
        .forEach(b -> types.put(b.getLeaveTypeId(), b.getLeaveTypeName())));
    List<String> columns = new ArrayList<>(List.of("Code", "Name"));
    types.values().forEach(name -> columns.addAll(List.of(name + " quota", name + " used",
        name + " available")));
    doc.setColumns(columns);
    for (LeaveBalanceRow row : rows) {
      List<String> cells = new ArrayList<>(List.of(text(row.getCode()), text(row.getName())));
      for (String typeId : types.keySet()) {
        LeaveBalanceRow.Balance b = row.getBalances().stream()
            .filter(x -> typeId.equals(x.getLeaveTypeId())).findFirst().orElse(null);
        cells.add(b == null || b.getQuota() == null ? "No limit" : number(b.getQuota()));
        cells.add(b == null ? "0" : number(b.getUsed()));
        cells.add(b == null || b.getAvailable() == null ? "No limit" : number(b.getAvailable()));
      }
      doc.getRows().add(cells);
    }
    doc.setKpis(new ArrayList<>(List.of(
        new MisDocumentKpi("Employees", String.valueOf(rows.size())))));
    doc.setDetailSheetTitle("Balances");
    return file("leave_balance_xlsx", () -> excelService.generateExcel(doc),
        "leave-balances-" + leaveYear + ".xlsx", HrFile.XLSX);
  }

  // ---- salary register -----------------------------------------------------------------------

  /**
   * {@code GET /payroll-runs/{runId}/register?format=xlsx|pdf} (VIEW_SALARIES, R12.2): one row
   * per employee with pay, deductions, net and payment state; any run status (a draft is marked
   * as such in the summary).
   */
  public HrFile salaryRegister(String userId, String shopId, String runId, String format) {
    guard.require(userId, shopId, HrPermission.VIEW_SALARIES);
    boolean pdf = parseFormat(format);
    record Loaded(PayrollRun run, List<PayrollLine> lines) {}
    Loaded loaded = queryRunner.read("salary_register", () -> {
      PayrollRun run = runRepository.findByShopIdAndId(shopId, runId)
          .orElseThrow(() -> new ResourceNotFoundException("PayrollRun", "id", runId));
      return new Loaded(run, lineRepository.findByShopIdAndRunId(shopId, runId));
    });
    PayrollRun run = loaded.run();
    List<PayrollLine> lines = new ArrayList<>(loaded.lines());
    lines.sort(Comparator.comparing(l -> l.getEmployeeSnapshot() != null
        ? String.valueOf(l.getEmployeeSnapshot().getCode()) : ""));
    String label = PayslipService.periodLabel(run.getPeriod(), run.getFromDate(),
        run.getToDate());
    // a salary month that is not the calendar month reads "Salary for 26 Sep – 25 Oct 2026"
    MisTabularDocumentRequest doc = base("Salary register", shopId,
        label.equals(PayslipService.periodLabel(run.getPeriod())) ? label : "Salary for " + label);
    doc.setColumns(new ArrayList<>(List.of("Code", "Name", "Designation", "Paid days", "Gross",
        "Advance recovered", "Other deductions", "Rounding", "Net pay", "Payment", "Paid on")));
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal net = BigDecimal.ZERO;
    int paid = 0;
    for (PayrollLine l : lines) {
      PayrollLine.EmployeeSnapshot snap = l.getEmployeeSnapshot() != null
          ? l.getEmployeeSnapshot() : new PayrollLine.EmployeeSnapshot();
      BigDecimal recovered = HrAccountingPoster.recoveredOn(l);
      BigDecimal other = nz(l.getTotalDeductions()).subtract(recovered);
      SalaryPaymentStatus status = l.getPaymentStatus() != null ? l.getPaymentStatus()
          : SalaryPaymentStatus.PENDING;
      doc.getRows().add(new ArrayList<>(List.of(text(snap.getCode()), text(snap.getName()),
          text(snap.getDesignation()),
          l.getDayCounts() != null ? number(l.getDayCounts().getPaidDays()) : "",
          money(l.getGross()), money(recovered), money(other), money(l.getRounding()),
          money(l.getNet()), status == SalaryPaymentStatus.PAID ? "Paid" : "Pending",
          text(l.getPaidAt()))));
      gross = gross.add(nz(l.getGross()));
      net = net.add(nz(l.getNet()));
      if (status == SalaryPaymentStatus.PAID) {
        paid++;
      }
    }
    doc.setKpis(new ArrayList<>(List.of(
        new MisDocumentKpi("Status", run.getStatus() != null ? run.getStatus().name() : ""),
        new MisDocumentKpi("Employees", String.valueOf(lines.size())),
        new MisDocumentKpi("Gross pay", money(gross)),
        new MisDocumentKpi("Net pay", money(net)),
        new MisDocumentKpi("Paid", paid + " of " + lines.size()))));
    doc.setDetailSheetTitle("Salaries");
    String name = "salary-register-" + run.getPeriod() + (pdf ? ".pdf" : ".xlsx");
    return pdf
        ? file("salary_register_pdf", () -> pdfService.generatePdf(doc), name, HrFile.PDF)
        : file("salary_register_xlsx", () -> excelService.generateExcel(doc), name, HrFile.XLSX);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private MisTabularDocumentRequest base(String title, String shopId, String periodLabel) {
    MisTabularDocumentRequest doc = new MisTabularDocumentRequest();
    doc.setTitle(title);
    doc.setColumns(new ArrayList<>());
    doc.setRows(new ArrayList<>());
    doc.setKpis(new ArrayList<>());
    ShopServiceAdapter.ShopSummary shop = shopServiceAdapter != null
        ? shopServiceAdapter.getShopSummary(shopId) : null;
    doc.setShopName(shop != null && shop.getName() != null ? shop.getName() : "");
    doc.setPeriodLabel(periodLabel);
    doc.setGeneratedAtLabel(GENERATED.format(ZonedDateTime.now(HrDateUtils.ZONE)));
    return doc;
  }

  private HrFile file(String operation, Supplier<byte[]> build, String name, String type) {
    long started = System.nanoTime();
    byte[] bytes = build.get();
    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
    metrics.increment(HrMetricsConstants.DOCUMENTS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, "operation", operation);
    metrics.increment(HrMetricsConstants.DOCUMENTS_SECONDS_TOTAL, seconds,
        "module", HrMetricsConstants.MODULE, "operation", operation);
    return new HrFile(name, type, bytes);
  }

  /** {@code xlsx} (default) or {@code pdf}; true = pdf. */
  static boolean parseFormat(String format) {
    if (format == null || format.isBlank() || "xlsx".equalsIgnoreCase(format.trim())) {
      return false;
    }
    if ("pdf".equalsIgnoreCase(format.trim())) {
      return true;
    }
    throw new ValidationException("format must be xlsx or pdf");
  }

  /**
   * Register letters (as the UI legend): P A H L W Ho, "-" unmarked, blank not employed or a day
   * after today that is not marked yet.
   */
  static String letter(MonthRegisterResponse.Cell cell) {
    DayStatus s = cell.getStatus();
    if (s == null) {
      return "";
    }
    String letter = switch (s) {
      case PRESENT -> "P";
      case ABSENT -> "A";
      case HALF_DAY -> "H";
      case LEAVE -> Boolean.TRUE.equals(cell.getHalfDayLeave()) ? "L/2" : "L";
      case WEEK_OFF -> "W";
      case HOLIDAY -> "Ho";
      case UNMARKED -> Boolean.TRUE.equals(cell.getFuture()) ? "" : "-";
      case NOT_EMPLOYED -> "";
    };
    return Boolean.TRUE.equals(cell.getLate()) ? letter + "*" : letter;
  }

  private static String number(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static String money(BigDecimal value) {
    return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  private static String text(String value) {
    return value != null ? value : "";
  }
}
