package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.domain.MisMoneyFilter;
import com.inventory.analytics.mis.domain.MisVendorTxnType;
import com.inventory.analytics.mis.rest.dto.MisMoneyPartySummaryDto;
import com.inventory.analytics.mis.rest.dto.MisMoneyReportResponse;
import com.inventory.analytics.mis.rest.dto.MisMoneyRowDto;
import com.inventory.analytics.mis.rest.dto.MisMoneySummaryDto;
import com.inventory.analytics.mis.support.MisDateRangeHelper;
import com.inventory.analytics.mis.support.MisMoneyTenderHelper;
import com.inventory.analytics.mis.support.MisMoneyTenderHelper.TenderSplit;
import com.inventory.analytics.mis.support.MisReportSupport;
import com.inventory.analytics.mis.support.MisTabularDocumentFactory;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditDirection;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.service.CreditService;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.service.MisProductQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VendorMoneyMisService {

  private static final String PURCHASE_CREDIT_PREFIX = "PURCHASE:CREDIT:";
  private static final String RETURN_CREDIT_PREFIX = "RETURN:CREDIT:";
  private static final String SALE_CREDIT_PREFIX = "SALE:CREDIT:";

  private final CreditService creditService;
  private final MisProductQueryService misProductQueryService;
  private final DocumentService documentService;
  private final MetricsWrapper metrics;

  public MisMoneyReportResponse getReport(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String vendorId,
      String txnTypesCsv,
      String moneyFilterRaw,
      String q,
      Integer page,
      Integer size) {
    metrics.record(
        AnalyticsMetricsConstants.MIS_REPORTS_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        "vendor_money");
    BuiltReport built =
        build(shopId, fromIn, toIn, vendorId, txnTypesCsv, moneyFilterRaw, q);
    int p = MisReportSupport.safePage(page);
    int s = MisReportSupport.safeSize(size);
    return MisMoneyReportResponse.builder()
        .from(built.from())
        .to(built.to())
        .summary(built.summary())
        .rows(MisReportSupport.paginate(built.filteredRows(), p, s))
        .page(p)
        .size(s)
        .totalItems(built.filteredRows().size())
        .build();
  }

  public byte[] exportExcel(
      String shopId,
      String shopName,
      LocalDate fromIn,
      LocalDate toIn,
      String vendorId,
      String txnTypesCsv,
      String moneyFilterRaw,
      String q) {
    BuiltReport built =
        build(shopId, fromIn, toIn, vendorId, txnTypesCsv, moneyFilterRaw, q);
    MisReportSupport.assertExportSize(built.filteredRows().size());
    MisTabularDocumentRequest doc =
        MisTabularDocumentFactory.moneyReport(
            "Vendor Money MIS",
            shopName,
            LocalDateTime.now(),
            built.from() + " to " + built.to(),
            built.summary(),
            built.filteredRows(),
            true);
    return documentService.generateMisExcel(doc);
  }

  public byte[] exportPdf(
      String shopId,
      String shopName,
      LocalDate fromIn,
      LocalDate toIn,
      String vendorId,
      String txnTypesCsv,
      String moneyFilterRaw,
      String q) {
    BuiltReport built =
        build(shopId, fromIn, toIn, vendorId, txnTypesCsv, moneyFilterRaw, q);
    MisReportSupport.assertExportSize(built.filteredRows().size());
    MisTabularDocumentRequest doc =
        MisTabularDocumentFactory.moneyReport(
            "Vendor Money MIS",
            shopName,
            LocalDateTime.now(),
            built.from() + " to " + built.to(),
            built.summary(),
            built.filteredRows(),
            true);
    return documentService.generateMisPdf(doc);
  }

  private BuiltReport build(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String vendorIdFilter,
      String txnTypesCsv,
      String moneyFilterRaw,
      String q) {
    LocalDate from = MisDateRangeHelper.resolveFrom(fromIn);
    LocalDate to = MisDateRangeHelper.resolveTo(toIn);
    if (to.isBefore(from)) {
      LocalDate swap = from;
      from = to;
      to = swap;
    }
    MisMoneyFilter moneyFilter = MisMoneyFilter.fromParam(moneyFilterRaw);
    Set<MisVendorTxnType> typeFilter = parseTxnTypes(txnTypesCsv);
    String query = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;
    String vendorFilter =
        StringUtils.hasText(vendorIdFilter) ? vendorIdFilter.trim() : null;

    Instant fromInst = MisDateRangeHelper.startOfDay(from);
    Instant toInst = MisDateRangeHelper.startOfNextDay(to);

    List<CreditAccount> accounts =
        creditService.listAccountsByPartyType(shopId, CreditPartyType.VENDOR);
    List<CreditEntry> allEntries =
        creditService.listEntriesByPartyType(shopId, CreditPartyType.VENDOR);

    List<VendorPurchaseInvoice> invoices =
        misProductQueryService.findVendorInvoicesByInvoiceDate(shopId, fromInst, toInst);
    // Also need invoices before `from`? Only for opening via credit. Returns/invoices in range only.
    List<VendorPurchaseReturn> returns =
        misProductQueryService.findVendorReturnsByCreatedAt(shopId, fromInst, toInst);

    Map<String, VendorPurchaseInvoice> invoiceById = new HashMap<>();
    for (VendorPurchaseInvoice inv :
        misProductQueryService.findAllVendorInvoices(shopId)) {
      if (inv.getId() != null) {
        invoiceById.put(inv.getId(), inv);
      }
    }

    Set<String> vendorIds = new HashSet<>();
    accounts.forEach(a -> {
      if (StringUtils.hasText(a.getPartyRefId())) {
        vendorIds.add(a.getPartyRefId().trim());
      }
    });
    invoices.forEach(i -> {
      if (StringUtils.hasText(i.getVendorId())) {
        vendorIds.add(i.getVendorId().trim());
      }
    });
    for (VendorPurchaseReturn r : returns) {
      VendorPurchaseInvoice inv = invoiceById.get(r.getVendorPurchaseInvoiceId());
      if (inv != null && StringUtils.hasText(inv.getVendorId())) {
        vendorIds.add(inv.getVendorId().trim());
      }
    }
    allEntries.forEach(e -> {
      if (StringUtils.hasText(e.getPartyRefId())) {
        vendorIds.add(e.getPartyRefId().trim());
      }
    });
    if (vendorFilter != null) {
      vendorIds.removeIf(id -> !id.equals(vendorFilter));
      vendorIds.add(vendorFilter);
    }

    Map<String, String> vendorNames = misProductQueryService.resolveVendorNames(vendorIds);
    Map<String, CreditAccount> accountByVendor = new HashMap<>();
    for (CreditAccount a : accounts) {
      if (StringUtils.hasText(a.getPartyRefId())) {
        accountByVendor.put(a.getPartyRefId().trim(), a);
        vendorNames.putIfAbsent(
            a.getPartyRefId().trim(),
            StringUtils.hasText(a.getPartyDisplayName())
                ? a.getPartyDisplayName().trim()
                : a.getPartyRefId());
      }
    }

    Map<String, List<CreditEntry>> entriesByVendor =
        allEntries.stream()
            .filter(e -> StringUtils.hasText(e.getPartyRefId()))
            .collect(Collectors.groupingBy(e -> e.getPartyRefId().trim()));

    List<MisMoneyRowDto> allRows = new ArrayList<>();
    List<MisMoneyPartySummaryDto> partySummaries = new ArrayList<>();

    BigDecimal openingTotal = BigDecimal.ZERO;
    BigDecimal cashTotal = BigDecimal.ZERO;
    BigDecimal onlineTotal = BigDecimal.ZERO;
    BigDecimal creditTotal = BigDecimal.ZERO;
    BigDecimal purchaseTotal = BigDecimal.ZERO;
    BigDecimal currentTotal = BigDecimal.ZERO;

    for (String vendorId : vendorIds.stream().sorted().toList()) {
      String vendorName = vendorNames.getOrDefault(vendorId, "Vendor");
      CreditAccount account = accountByVendor.get(vendorId);
      BigDecimal current =
          MisMoneyTenderHelper.nz(account != null ? account.getCurrentBalance() : BigDecimal.ZERO);
      List<CreditEntry> vendorEntries =
          entriesByVendor.getOrDefault(vendorId, List.of());

      BigDecimal opening = computeOpening(current, vendorEntries, from);
      List<MisMoneyRowDto> periodEvents = new ArrayList<>();

      for (VendorPurchaseInvoice inv : invoices) {
        if (!vendorId.equals(trim(inv.getVendorId()))) {
          continue;
        }
        periodEvents.add(fromInvoice(inv, vendorId, vendorName));
      }
      for (VendorPurchaseReturn ret : returns) {
        VendorPurchaseInvoice inv = invoiceById.get(ret.getVendorPurchaseInvoiceId());
        if (inv == null || !vendorId.equals(trim(inv.getVendorId()))) {
          continue;
        }
        periodEvents.add(fromReturn(ret, inv, vendorId, vendorName));
      }
      for (CreditEntry entry : vendorEntries) {
        LocalDate d = MisDateRangeHelper.businessDate(entry.getTxnDate(), entry.getCreatedAt());
        if (d == null || d.isBefore(from) || d.isAfter(to)) {
          continue;
        }
        MisMoneyRowDto mapped = fromCreditEntry(entry, vendorId, vendorName);
        if (mapped != null) {
          periodEvents.add(mapped);
        }
      }

      periodEvents.sort(
          Comparator.comparing(MisMoneyRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(
                  MisMoneyRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(MisMoneyRowDto::getTxnId, Comparator.nullsLast(Comparator.naturalOrder())));

      // No brought-forward balance and no period activity → omit from report.
      if (opening.signum() == 0 && periodEvents.isEmpty()) {
        continue;
      }

      List<MisMoneyRowDto> withOpening = new ArrayList<>();
      if (opening.signum() != 0) {
        withOpening.add(
            MisMoneyRowDto.builder()
                .txnId("OPENING:" + vendorId)
                .txnType(MisVendorTxnType.OPENING.name())
                .txnTypeLabel(MisVendorTxnType.OPENING.label())
                .partyId(vendorId)
                .partyName(vendorName)
                .txnDate(from)
                .postedAt(null)
                .refNo(null)
                .totalAmount(BigDecimal.ZERO)
                .cashAmount(BigDecimal.ZERO)
                .onlineAmount(BigDecimal.ZERO)
                .creditAmount(BigDecimal.ZERO)
                .balanceAfter(opening)
                .sourceType("OPENING")
                .sourceId(vendorId)
                .opening(true)
                .build());
      }

      BigDecimal running = opening;
      BigDecimal closing = opening;
      for (MisMoneyRowDto ev : periodEvents) {
        running = applyPayableDelta(running, ev);
        ev.setBalanceAfter(running);
        withOpening.add(ev);
        closing = running;
      }

      partySummaries.add(
          MisMoneyPartySummaryDto.builder()
              .partyId(vendorId)
              .partyName(vendorName)
              .openingBalance(opening)
              .closingBalanceInPeriod(closing)
              .currentBalance(current)
              .build());

      openingTotal = openingTotal.add(opening);
      currentTotal = currentTotal.add(current);

      for (MisMoneyRowDto row : withOpening) {
        if (!row.isOpening()) {
          cashTotal = cashTotal.add(MisMoneyTenderHelper.nz(row.getCashAmount()));
          onlineTotal = onlineTotal.add(MisMoneyTenderHelper.nz(row.getOnlineAmount()));
          creditTotal = creditTotal.add(MisMoneyTenderHelper.nz(row.getCreditAmount()));
          if (MisVendorTxnType.PURCHASE.name().equals(row.getTxnType())) {
            purchaseTotal = purchaseTotal.add(MisMoneyTenderHelper.nz(row.getTotalAmount()));
          }
        }
        if (passesFilters(row, typeFilter, moneyFilter, query)) {
          allRows.add(row);
        }
      }
    }

    allRows.sort(
        Comparator.comparing(MisMoneyRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MisMoneyRowDto::getPartyName, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(MisMoneyRowDto::getTxnId, Comparator.nullsLast(Comparator.naturalOrder())));

    MisMoneySummaryDto summary =
        MisMoneySummaryDto.builder()
            .openingBalanceTotal(MisMoneyTenderHelper.nz(openingTotal))
            .periodCashTotal(MisMoneyTenderHelper.nz(cashTotal))
            .periodOnlineTotal(MisMoneyTenderHelper.nz(onlineTotal))
            .periodCreditTotal(MisMoneyTenderHelper.nz(creditTotal))
            .periodPurchaseOrSaleTotal(MisMoneyTenderHelper.nz(purchaseTotal))
            .currentBalanceTotal(MisMoneyTenderHelper.nz(currentTotal))
            .partySummaries(partySummaries)
            .build();

    return new BuiltReport(from, to, summary, allRows);
  }

  private static BigDecimal computeOpening(
      BigDecimal current, List<CreditEntry> entries, LocalDate from) {
    BigDecimal netOnOrAfter = BigDecimal.ZERO;
    for (CreditEntry e : entries) {
      LocalDate d = MisDateRangeHelper.businessDate(e.getTxnDate(), e.getCreatedAt());
      if (d == null || d.isBefore(from)) {
        continue;
      }
      BigDecimal amt = MisMoneyTenderHelper.nz(e.getAmount());
      if (e.getDirection() == CreditDirection.INCREASE_DUE) {
        netOnOrAfter = netOnOrAfter.add(amt);
      } else if (e.getDirection() == CreditDirection.DECREASE_DUE) {
        netOnOrAfter = netOnOrAfter.subtract(amt);
      }
    }
    return MisMoneyTenderHelper.nz(current.subtract(netOnOrAfter));
  }

  /**
   * Payable rises on purchase/credit charge (credit leg), falls on payment and return credit
   * reduction / cash-online refunds from vendor.
   */
  private static BigDecimal applyPayableDelta(BigDecimal running, MisMoneyRowDto ev) {
    BigDecimal bal = MisMoneyTenderHelper.nz(running);
    MisVendorTxnType type = MisVendorTxnType.fromParam(ev.getTxnType());
    if (type == null) {
      return bal;
    }
    return switch (type) {
      case PURCHASE, CREDIT_CHARGE -> bal.add(MisMoneyTenderHelper.nz(ev.getCreditAmount()));
      case PAYMENT -> {
        BigDecimal paid =
            MisMoneyTenderHelper.nz(ev.getCashAmount())
                .add(MisMoneyTenderHelper.nz(ev.getOnlineAmount()));
        if (paid.signum() == 0) {
          paid = MisMoneyTenderHelper.nz(ev.getTotalAmount());
        }
        yield bal.subtract(paid);
      }      case RETURN ->
          bal.subtract(MisMoneyTenderHelper.nz(ev.getCreditAmount()))
              .subtract(MisMoneyTenderHelper.nz(ev.getCashAmount()))
              .subtract(MisMoneyTenderHelper.nz(ev.getOnlineAmount()));
      case OPENING -> bal;
    };
  }

  private static MisMoneyRowDto fromInvoice(
      VendorPurchaseInvoice inv, String vendorId, String vendorName) {
    BigDecimal total = MisMoneyTenderHelper.nz(inv.getInvoiceTotal());
    BigDecimal paid = MisMoneyTenderHelper.nz(inv.getPaidAmount());
    if (paid.compareTo(total) > 0) {
      paid = total;
    }
    TenderSplit split = MisMoneyTenderHelper.splitPaid(paid, inv.getPaymentMethod());
    BigDecimal credit = total.subtract(paid);
    if (credit.signum() < 0) {
      credit = BigDecimal.ZERO;
    }
    LocalDate date =
        MisDateRangeHelper.toLocalDate(
            inv.getInvoiceDate() != null ? inv.getInvoiceDate() : inv.getCreatedAt());
    return MisMoneyRowDto.builder()
        .txnId(MisReportSupport.resolveTxnId(inv.getTxnId(), inv.getId()))
        .txnType(MisVendorTxnType.PURCHASE.name())
        .txnTypeLabel(MisVendorTxnType.PURCHASE.label())
        .partyId(vendorId)
        .partyName(vendorName)
        .txnDate(date)
        .postedAt(inv.getCreatedAt())
        .refNo(inv.getInvoiceNo())
        .totalAmount(total)
        .cashAmount(split.cash())
        .onlineAmount(split.online())
        .creditAmount(MisMoneyTenderHelper.nz(credit))
        .sourceType("VENDOR_PURCHASE_INVOICE")
        .sourceId(inv.getId())
        .opening(false)
        .build();
  }

  private static MisMoneyRowDto fromReturn(
      VendorPurchaseReturn ret,
      VendorPurchaseInvoice inv,
      String vendorId,
      String vendorName) {
    BigDecimal cash = MisMoneyTenderHelper.nz(ret.getRefundCash());
    BigDecimal online = MisMoneyTenderHelper.nz(ret.getRefundOnline());
    BigDecimal credit = MisMoneyTenderHelper.nz(ret.getRefundToCredit());
    BigDecimal total = MisMoneyTenderHelper.nz(ret.getReturnAmount());
    if (total.signum() == 0) {
      total = cash.add(online).add(credit);
    }
    return MisMoneyRowDto.builder()
        .txnId(MisReportSupport.resolveTxnId(ret.getTxnId(), ret.getId()))
        .txnType(MisVendorTxnType.RETURN.name())
        .txnTypeLabel(MisVendorTxnType.RETURN.label())
        .partyId(vendorId)
        .partyName(vendorName)
        .txnDate(MisDateRangeHelper.toLocalDate(ret.getCreatedAt()))
        .postedAt(ret.getCreatedAt())
        .refNo(ret.getSupplierCreditNoteNo())
        .againstTxnId(inv.getId())
        .againstRefNo(inv.getInvoiceNo())
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceType("VENDOR_PURCHASE_RETURN")
        .sourceId(ret.getId())
        .opening(false)
        .build();
  }

  private static MisMoneyRowDto fromCreditEntry(
      CreditEntry entry, String vendorId, String vendorName) {
    if (entry.getEntryType() == CreditEntryType.SETTLEMENT) {
      TenderSplit split =
          MisMoneyTenderHelper.splitPaid(entry.getAmount(), entry.getPaymentMethod());
      BigDecimal total = MisMoneyTenderHelper.nz(entry.getAmount());
      boolean adjustment = MisMoneyTenderHelper.isAdjustmentMethod(entry.getPaymentMethod());
      return MisMoneyRowDto.builder()
          .txnId(MisReportSupport.resolveTxnId(entry.getTxnId(), entry.getId()))
          .txnType(MisVendorTxnType.PAYMENT.name())
          .txnTypeLabel(MisVendorTxnType.PAYMENT.label())
          .partyId(vendorId)
          .partyName(vendorName)
          .txnDate(MisDateRangeHelper.businessDate(entry.getTxnDate(), entry.getCreatedAt()))
          .postedAt(entry.getCreatedAt())
          .refNo(entry.getBankRef())
          .totalAmount(total)
          .cashAmount(adjustment ? BigDecimal.ZERO : split.cash())
          .onlineAmount(adjustment ? BigDecimal.ZERO : split.online())
          .creditAmount(BigDecimal.ZERO)
          .sourceType("CREDIT_SETTLEMENT")
          .sourceId(entry.getId())
          .opening(false)
          .build();
    }
    if (entry.getEntryType() == CreditEntryType.CHARGE && isManualCharge(entry)) {
      BigDecimal total = MisMoneyTenderHelper.nz(entry.getAmount());
      return MisMoneyRowDto.builder()
          .txnId(MisReportSupport.resolveTxnId(entry.getTxnId(), entry.getId()))
          .txnType(MisVendorTxnType.CREDIT_CHARGE.name())
          .txnTypeLabel(MisVendorTxnType.CREDIT_CHARGE.label())
          .partyId(vendorId)
          .partyName(vendorName)
          .txnDate(MisDateRangeHelper.businessDate(entry.getTxnDate(), entry.getCreatedAt()))
          .postedAt(entry.getCreatedAt())
          .refNo(entry.getNote())
          .totalAmount(total)
          .cashAmount(BigDecimal.ZERO)
          .onlineAmount(BigDecimal.ZERO)
          .creditAmount(total)
          .sourceType("CREDIT_CHARGE")
          .sourceId(entry.getId())
          .opening(false)
          .build();
    }
    return null;
  }

  private static boolean isManualCharge(CreditEntry entry) {
    String sk = entry.getSourceKey();
    if (StringUtils.hasText(sk)) {
      String u = sk.trim().toUpperCase(Locale.ROOT);
      if (u.startsWith(PURCHASE_CREDIT_PREFIX)
          || u.startsWith(RETURN_CREDIT_PREFIX)
          || u.startsWith(SALE_CREDIT_PREFIX)) {
        return false;
      }
    }
    String ref = entry.getReferenceType();
    if (StringUtils.hasText(ref)) {
      String r = ref.trim().toUpperCase(Locale.ROOT);
      if ("PURCHASE".equals(r) || "SALE".equals(r) || "RETURN".equals(r)) {
        return false;
      }
    }
    return true;
  }

  private static boolean passesFilters(
      MisMoneyRowDto row,
      Set<MisVendorTxnType> typeFilter,
      MisMoneyFilter moneyFilter,
      String query) {
    if (row.isOpening()) {
      return true;
    }
    if (typeFilter != null && !typeFilter.isEmpty()) {
      MisVendorTxnType t = MisVendorTxnType.fromParam(row.getTxnType());
      if (t == null || !typeFilter.contains(t)) {
        return false;
      }
    }
    if (!MisReportSupport.matchesMoneyFilter(
        moneyFilter, row.getCashAmount(), row.getOnlineAmount(), row.getCreditAmount())) {
      return false;
    }
    if (query != null) {
      String hay =
          (nullToEmpty(row.getPartyName())
                  + " "
                  + nullToEmpty(row.getRefNo())
                  + " "
                  + nullToEmpty(row.getTxnId())
                  + " "
                  + nullToEmpty(row.getSourceId()))
              .toLowerCase(Locale.ROOT);
      if (!hay.contains(query)) {
        return false;
      }
    }
    return true;
  }

  private static Set<MisVendorTxnType> parseTxnTypes(String csv) {
    if (!StringUtils.hasText(csv)) {
      return Set.of();
    }
    Set<MisVendorTxnType> out = new HashSet<>();
    for (String part : csv.split(",")) {
      MisVendorTxnType t = MisVendorTxnType.fromParam(part);
      if (t != null && t != MisVendorTxnType.OPENING) {
        out.add(t);
      }
    }
    return out;
  }

  private static String trim(String s) {
    return StringUtils.hasText(s) ? s.trim() : null;
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }

  private record BuiltReport(
      LocalDate from,
      LocalDate to,
      MisMoneySummaryDto summary,
      List<MisMoneyRowDto> filteredRows) {}
}
