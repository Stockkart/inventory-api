package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.domain.MisCustomerTxnType;
import com.inventory.analytics.mis.domain.MisMoneyFilter;
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
import com.inventory.credit.api.CreditAccountView;
import com.inventory.credit.api.CreditDirection;
import com.inventory.credit.api.CreditEntryType;
import com.inventory.credit.api.CreditEntryView;
import com.inventory.credit.api.CreditLedger;
import com.inventory.credit.api.CreditPartyType;
import com.inventory.documentservice.api.MisDocuments;
import com.inventory.documentservice.api.MisTabularDocument;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
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
public class CustomerMoneyMisService {

  private static final String PURCHASE_CREDIT_PREFIX = "PURCHASE:CREDIT:";
  private static final String RETURN_CREDIT_PREFIX = "RETURN:CREDIT:";
  private static final String SALE_CREDIT_PREFIX = "SALE:CREDIT:";

  private final CreditLedger creditLedger;
  private final MisProductQueryService misProductQueryService;
  private final MisDocuments misDocuments;
  private final SalesMisService salesMisService;
  private final MetricsWrapper metrics;

  public MisMoneyReportResponse getReport(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String customerId,
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
        "customer_money");
    BuiltReport built =
        build(shopId, fromIn, toIn, customerId, txnTypesCsv, moneyFilterRaw, q);
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
      String customerId,
      String txnTypesCsv,
      String moneyFilterRaw,
      String q) {
    BuiltReport built =
        build(shopId, fromIn, toIn, customerId, txnTypesCsv, moneyFilterRaw, q);
    SalesMisService.BuiltReport sales =
        salesMisService.buildReport(shopId, fromIn, toIn, null, customerId, null);
    MisReportSupport.assertExportSize(built.filteredRows().size());
    MisReportSupport.assertExportSize(sales.rows().size());
    MisTabularDocument doc =
        MisTabularDocumentFactory.customerMisReport(
            shopName,
            LocalDateTime.now(),
            built.from() + " to " + built.to(),
            built.summary(),
            built.filteredRows(),
            sales.summary(),
            sales.rows());
    return misDocuments.excel(doc);
  }

  public byte[] exportPdf(
      String shopId,
      String shopName,
      LocalDate fromIn,
      LocalDate toIn,
      String customerId,
      String txnTypesCsv,
      String moneyFilterRaw,
      String q) {
    BuiltReport built =
        build(shopId, fromIn, toIn, customerId, txnTypesCsv, moneyFilterRaw, q);
    MisReportSupport.assertExportSize(built.filteredRows().size());
    MisTabularDocument doc =
        MisTabularDocumentFactory.moneyReport(
            "Customer Money MIS",
            shopName,
            LocalDateTime.now(),
            built.from() + " to " + built.to(),
            built.summary(),
            built.filteredRows(),
            false);
    return misDocuments.pdf(doc);
  }

  private BuiltReport build(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String customerIdFilter,
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
    Set<MisCustomerTxnType> typeFilter = parseTxnTypes(txnTypesCsv);
    String query = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;
    String customerFilter =
        StringUtils.hasText(customerIdFilter) ? customerIdFilter.trim() : null;

    Instant fromInst = MisDateRangeHelper.startOfDay(from);
    Instant toInst = MisDateRangeHelper.startOfNextDay(to);

    List<CreditAccountView> accounts =
        creditLedger.accounts(shopId, CreditPartyType.CUSTOMER);
    List<CreditEntryView> allEntries =
        creditLedger.entries(shopId, CreditPartyType.CUSTOMER);

    List<Purchase> sales =
        misProductQueryService.findCompletedSalesBySoldAt(shopId, fromInst, toInst);
    List<Refund> refunds =
        misProductQueryService.findRefundsByCreatedAt(shopId, fromInst, toInst);

    Map<String, Purchase> saleById = new HashMap<>();
    for (Purchase p : sales) {
      if (p.getId() != null) {
        saleById.put(p.getId(), p);
      }
    }

    Set<String> customerIds = new HashSet<>();
    accounts.forEach(
        a -> {
          if (StringUtils.hasText(a.partyRefId())) {
            customerIds.add(a.partyRefId().trim());
          }
        });
    sales.forEach(
        p -> {
          if (StringUtils.hasText(p.getCustomerId())) {
            customerIds.add(p.getCustomerId().trim());
          }
        });
    refunds.forEach(
        r -> {
          if (StringUtils.hasText(r.getCustomerId())) {
            customerIds.add(r.getCustomerId().trim());
          } else {
            Purchase against = saleById.get(r.getPurchaseId());
            if (against != null && StringUtils.hasText(against.getCustomerId())) {
              customerIds.add(against.getCustomerId().trim());
            }
          }
        });
    allEntries.forEach(
        e -> {
          if (StringUtils.hasText(e.partyRefId())) {
            customerIds.add(e.partyRefId().trim());
          }
        });
    if (customerFilter != null) {
      customerIds.removeIf(id -> !id.equals(customerFilter));
      customerIds.add(customerFilter);
    }

    Map<String, String> customerNames = misProductQueryService.resolveCustomerNames(customerIds);
    Map<String, CreditAccountView> accountByCustomer = new HashMap<>();
    for (CreditAccountView a : accounts) {
      if (StringUtils.hasText(a.partyRefId())) {
        accountByCustomer.put(a.partyRefId().trim(), a);
        customerNames.putIfAbsent(
            a.partyRefId().trim(),
            StringUtils.hasText(a.partyDisplayName())
                ? a.partyDisplayName().trim()
                : a.partyRefId());
      }
    }
    for (Purchase p : sales) {
      if (StringUtils.hasText(p.getCustomerId()) && StringUtils.hasText(p.getCustomerName())) {
        customerNames.putIfAbsent(p.getCustomerId().trim(), p.getCustomerName().trim());
      }
    }

    Map<String, List<CreditEntryView>> entriesByCustomer =
        allEntries.stream()
            .filter(e -> StringUtils.hasText(e.partyRefId()))
            .collect(Collectors.groupingBy(e -> e.partyRefId().trim()));

    List<MisMoneyRowDto> allRows = new ArrayList<>();
    List<MisMoneyPartySummaryDto> partySummaries = new ArrayList<>();

    BigDecimal openingTotal = BigDecimal.ZERO;
    BigDecimal cashTotal = BigDecimal.ZERO;
    BigDecimal onlineTotal = BigDecimal.ZERO;
    BigDecimal creditTotal = BigDecimal.ZERO;
    BigDecimal saleTotal = BigDecimal.ZERO;
    BigDecimal currentTotal = BigDecimal.ZERO;

    for (String customerId : customerIds.stream().sorted().toList()) {
      String customerName = customerNames.getOrDefault(customerId, "Customer");
      CreditAccountView account = accountByCustomer.get(customerId);
      BigDecimal current =
          MisMoneyTenderHelper.nz(account != null ? account.currentBalance() : BigDecimal.ZERO);
      List<CreditEntryView> customerEntries =
          entriesByCustomer.getOrDefault(customerId, List.of());

      BigDecimal opening = computeOpening(current, customerEntries, from);
      List<MisMoneyRowDto> periodEvents = new ArrayList<>();

      for (Purchase sale : sales) {
        if (!customerId.equals(trim(sale.getCustomerId()))) {
          continue;
        }
        periodEvents.add(fromSale(sale, customerId, customerName));
      }
      for (Refund refund : refunds) {
        Purchase against = saleById.get(refund.getPurchaseId());
        String refundCustomerId = trim(refund.getCustomerId());
        if (refundCustomerId == null && against != null) {
          refundCustomerId = trim(against.getCustomerId());
        }
        if (!customerId.equals(refundCustomerId)) {
          continue;
        }
        periodEvents.add(fromRefund(refund, against, customerId, customerName));
      }
      for (CreditEntryView entry : customerEntries) {
        LocalDate d = MisDateRangeHelper.businessDate(entry.txnDate(), entry.createdAt());
        if (d == null || d.isBefore(from) || d.isAfter(to)) {
          continue;
        }
        MisMoneyRowDto mapped = fromCreditEntry(entry, customerId, customerName);
        if (mapped != null) {
          periodEvents.add(mapped);
        }
      }

      periodEvents.sort(
          Comparator.comparing(
                  MisMoneyRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(
                  MisMoneyRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(
                  MisMoneyRowDto::getTxnId, Comparator.nullsLast(Comparator.naturalOrder())));

      // No brought-forward balance and no period activity → omit from report.
      if (opening.signum() == 0 && periodEvents.isEmpty()) {
        continue;
      }

      List<MisMoneyRowDto> withOpening = new ArrayList<>();
      if (opening.signum() != 0) {
        withOpening.add(
            MisMoneyRowDto.builder()
                .txnId("OPENING:" + customerId)
                .txnType(MisCustomerTxnType.OPENING.name())
                .txnTypeLabel(MisCustomerTxnType.OPENING.label())
                .partyId(customerId)
                .partyName(customerName)
                .txnDate(from)
                .postedAt(null)
                .refNo(null)
                .totalAmount(BigDecimal.ZERO)
                .cashAmount(BigDecimal.ZERO)
                .onlineAmount(BigDecimal.ZERO)
                .creditAmount(BigDecimal.ZERO)
                .balanceAfter(opening)
                .sourceType("OPENING")
                .sourceId(customerId)
                .opening(true)
                .build());
      }

      BigDecimal running = opening;
      BigDecimal closing = opening;
      for (MisMoneyRowDto ev : periodEvents) {
        running = applyReceivableDelta(running, ev);
        ev.setBalanceAfter(running);
        withOpening.add(ev);
        closing = running;
      }

      partySummaries.add(
          MisMoneyPartySummaryDto.builder()
              .partyId(customerId)
              .partyName(customerName)
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
          if (MisCustomerTxnType.SALE.name().equals(row.getTxnType())) {
            saleTotal = saleTotal.add(MisMoneyTenderHelper.nz(row.getTotalAmount()));
          }
        }
        if (passesFilters(row, typeFilter, moneyFilter, query)) {
          allRows.add(row);
        }
      }
    }

    allRows.sort(
        Comparator.comparing(
                MisMoneyRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                MisMoneyRowDto::getPartyName, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(
                MisMoneyRowDto::getTxnId, Comparator.nullsLast(Comparator.naturalOrder())));

    MisMoneySummaryDto summary =
        MisMoneySummaryDto.builder()
            .openingBalanceTotal(MisMoneyTenderHelper.nz(openingTotal))
            .periodCashTotal(MisMoneyTenderHelper.nz(cashTotal))
            .periodOnlineTotal(MisMoneyTenderHelper.nz(onlineTotal))
            .periodCreditTotal(MisMoneyTenderHelper.nz(creditTotal))
            .periodPurchaseOrSaleTotal(MisMoneyTenderHelper.nz(saleTotal))
            .currentBalanceTotal(MisMoneyTenderHelper.nz(currentTotal))
            .partySummaries(partySummaries)
            .build();

    return new BuiltReport(from, to, summary, allRows);
  }

  private static BigDecimal computeOpening(
      BigDecimal current, List<CreditEntryView> entries, LocalDate from) {
    BigDecimal netOnOrAfter = BigDecimal.ZERO;
    for (CreditEntryView e : entries) {
      LocalDate d = MisDateRangeHelper.businessDate(e.txnDate(), e.createdAt());
      if (d == null || d.isBefore(from)) {
        continue;
      }
      BigDecimal amt = MisMoneyTenderHelper.nz(e.amount());
      if (e.direction() == CreditDirection.INCREASE_DUE) {
        netOnOrAfter = netOnOrAfter.add(amt);
      } else if (e.direction() == CreditDirection.DECREASE_DUE) {
        netOnOrAfter = netOnOrAfter.subtract(amt);
      }
    }
    return MisMoneyTenderHelper.nz(current.subtract(netOnOrAfter));
  }

  /**
   * Receivable rises on sale/credit charge (credit leg), falls on collection and refund
   * cash/online/credit reduction.
   */
  private static BigDecimal applyReceivableDelta(BigDecimal running, MisMoneyRowDto ev) {
    BigDecimal bal = MisMoneyTenderHelper.nz(running);
    MisCustomerTxnType type = MisCustomerTxnType.fromParam(ev.getTxnType());
    if (type == null) {
      return bal;
    }
    return switch (type) {
      case SALE, CREDIT_CHARGE -> bal.add(MisMoneyTenderHelper.nz(ev.getCreditAmount()));
      case COLLECTION -> {
        BigDecimal collected =
            MisMoneyTenderHelper.nz(ev.getCashAmount())
                .add(MisMoneyTenderHelper.nz(ev.getOnlineAmount()));
        if (collected.signum() == 0) {
          collected = MisMoneyTenderHelper.nz(ev.getTotalAmount());
        }
        yield bal.subtract(collected);
      }
      case REFUND ->
          bal.subtract(MisMoneyTenderHelper.nz(ev.getCreditAmount()))
              .subtract(MisMoneyTenderHelper.nz(ev.getCashAmount()))
              .subtract(MisMoneyTenderHelper.nz(ev.getOnlineAmount()));
      case OPENING -> bal;
    };
  }

  private static MisMoneyRowDto fromSale(Purchase sale, String customerId, String customerName) {
    BigDecimal cash = MisMoneyTenderHelper.nz(sale.getCashAmount());
    BigDecimal online = MisMoneyTenderHelper.nz(sale.getOnlineAmount());
    BigDecimal credit = MisMoneyTenderHelper.nz(sale.getCreditAmount());
    BigDecimal total = MisMoneyTenderHelper.nz(sale.getGrandTotal());
    if (total.signum() == 0) {
      total = cash.add(online).add(credit);
    }
    LocalDate date =
        MisDateRangeHelper.toLocalDate(
            sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt());
    return MisMoneyRowDto.builder()
        .txnId(MisReportSupport.resolveTxnId(sale.getTxnId(), sale.getId()))
        .txnType(MisCustomerTxnType.SALE.name())
        .txnTypeLabel(MisCustomerTxnType.SALE.label())
        .partyId(customerId)
        .partyName(customerName)
        .txnDate(date)
        .postedAt(sale.getCreatedAt())
        .refNo(sale.getInvoiceNo())
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceType("PURCHASE")
        .sourceId(sale.getId())
        .opening(false)
        .build();
  }

  private static MisMoneyRowDto fromRefund(
      Refund refund, Purchase against, String customerId, String customerName) {
    BigDecimal cash = MisMoneyTenderHelper.nz(refund.getRefundCash());
    BigDecimal online = MisMoneyTenderHelper.nz(refund.getRefundOnline());
    BigDecimal credit = MisMoneyTenderHelper.nz(refund.getRefundToCredit());
    BigDecimal total = MisMoneyTenderHelper.nz(refund.getRefundAmount());
    if (total.signum() == 0) {
      total = cash.add(online).add(credit);
    }
    return MisMoneyRowDto.builder()
        .txnId(MisReportSupport.resolveTxnId(refund.getTxnId(), refund.getId()))
        .txnType(MisCustomerTxnType.REFUND.name())
        .txnTypeLabel(MisCustomerTxnType.REFUND.label())
        .partyId(customerId)
        .partyName(customerName)
        .txnDate(MisDateRangeHelper.toLocalDate(refund.getCreatedAt()))
        .postedAt(refund.getCreatedAt())
        .refNo(refund.getCreditNoteNo())
        .againstTxnId(refund.getPurchaseId())
        .againstRefNo(against != null ? against.getInvoiceNo() : null)
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceType("REFUND")
        .sourceId(refund.getId())
        .opening(false)
        .build();
  }

  private static MisMoneyRowDto fromCreditEntry(
      CreditEntryView entry, String customerId, String customerName) {
    if (entry.entryType() == CreditEntryType.SETTLEMENT) {
      TenderSplit split =
          MisMoneyTenderHelper.splitPaid(entry.amount(), entry.paymentMethod());
      BigDecimal total = MisMoneyTenderHelper.nz(entry.amount());
      boolean adjustment = MisMoneyTenderHelper.isAdjustmentMethod(entry.paymentMethod());
      return MisMoneyRowDto.builder()
          .txnId(MisReportSupport.resolveTxnId(entry.txnId(), entry.id()))
          .txnType(MisCustomerTxnType.COLLECTION.name())
          .txnTypeLabel(MisCustomerTxnType.COLLECTION.label())
          .partyId(customerId)
          .partyName(customerName)
          .txnDate(MisDateRangeHelper.businessDate(entry.txnDate(), entry.createdAt()))
          .postedAt(entry.createdAt())
          .refNo(entry.bankRef())
          .totalAmount(total)
          .cashAmount(adjustment ? BigDecimal.ZERO : split.cash())
          .onlineAmount(adjustment ? BigDecimal.ZERO : split.online())
          .creditAmount(BigDecimal.ZERO)
          .sourceType("CREDIT_SETTLEMENT")
          .sourceId(entry.id())
          .opening(false)
          .build();
    }
    if (entry.entryType() == CreditEntryType.CHARGE && isManualCharge(entry)) {
      BigDecimal total = MisMoneyTenderHelper.nz(entry.amount());
      return MisMoneyRowDto.builder()
          .txnId(MisReportSupport.resolveTxnId(entry.txnId(), entry.id()))
          .txnType(MisCustomerTxnType.CREDIT_CHARGE.name())
          .txnTypeLabel(MisCustomerTxnType.CREDIT_CHARGE.label())
          .partyId(customerId)
          .partyName(customerName)
          .txnDate(MisDateRangeHelper.businessDate(entry.txnDate(), entry.createdAt()))
          .postedAt(entry.createdAt())
          .refNo(entry.note())
          .totalAmount(total)
          .cashAmount(BigDecimal.ZERO)
          .onlineAmount(BigDecimal.ZERO)
          .creditAmount(total)
          .sourceType("CREDIT_CHARGE")
          .sourceId(entry.id())
          .opening(false)
          .build();
    }
    return null;
  }

  private static boolean isManualCharge(CreditEntryView entry) {
    String sk = entry.sourceKey();
    if (StringUtils.hasText(sk)) {
      String u = sk.trim().toUpperCase(Locale.ROOT);
      if (u.startsWith(PURCHASE_CREDIT_PREFIX)
          || u.startsWith(RETURN_CREDIT_PREFIX)
          || u.startsWith(SALE_CREDIT_PREFIX)) {
        return false;
      }
    }
    String ref = entry.referenceType();
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
      Set<MisCustomerTxnType> typeFilter,
      MisMoneyFilter moneyFilter,
      String query) {
    if (row.isOpening()) {
      return true;
    }
    if (typeFilter != null && !typeFilter.isEmpty()) {
      MisCustomerTxnType t = MisCustomerTxnType.fromParam(row.getTxnType());
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

  private static Set<MisCustomerTxnType> parseTxnTypes(String csv) {
    if (!StringUtils.hasText(csv)) {
      return Set.of();
    }
    Set<MisCustomerTxnType> out = new HashSet<>();
    for (String part : csv.split(",")) {
      MisCustomerTxnType t = MisCustomerTxnType.fromParam(part);
      if (t != null && t != MisCustomerTxnType.OPENING) {
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
