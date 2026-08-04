package com.inventory.analytics.service;

import static com.inventory.analytics.utils.SalesMisUtils.addDelta;
import static com.inventory.analytics.utils.SalesMisUtils.containsIgnoreCase;
import static com.inventory.analytics.utils.SalesMisUtils.endOfDayInclusive;
import static com.inventory.analytics.utils.SalesMisUtils.isNonZero;
import static com.inventory.analytics.utils.SalesMisUtils.isWithin;
import static com.inventory.analytics.utils.SalesMisUtils.lowerOrEmpty;
import static com.inventory.analytics.utils.SalesMisUtils.startOfDay;
import static com.inventory.analytics.utils.SalesMisUtils.toMoneyScale;
import static com.inventory.analytics.utils.SalesMisUtils.toShopDate;
import static com.inventory.analytics.utils.SalesMisUtils.zeroIfNull;
import static com.inventory.analytics.utils.SalesMisUtils.zeroMoney;

import com.inventory.analytics.domain.model.MoneyFilter;
import com.inventory.analytics.domain.model.SalesMisExportScope;
import com.inventory.analytics.domain.model.SalesMisTxnType;
import com.inventory.analytics.mapper.SalesMisMapper;
import com.inventory.analytics.rest.dto.response.SalesMisCustomerSummaryDto;
import com.inventory.analytics.rest.dto.response.SalesMisDailyRowDto;
import com.inventory.analytics.rest.dto.response.SalesMisResponse;
import com.inventory.analytics.rest.dto.response.SalesMisRowDto;
import com.inventory.analytics.rest.dto.response.SalesMisSummaryDto;
import com.inventory.analytics.utils.SalesMisUtils;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.service.CustomerLedgerReadService;
import com.inventory.documentservice.rest.dto.TabularReport;
import com.inventory.documentservice.service.MISReportService;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.service.SalesLedgerReadService;
import com.inventory.product.service.VendorPurchasePaymentBreakdown;
import com.inventory.user.service.CustomerDirectoryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Sales Money MIS: Excel-style row ledger of sale / receipt / return / charge events with
 * cash/online/credit columns and running receivable balance per customer.
 *
 * <p>Receivable counterpart of {@link VendorMoneyMisService}, and structured the same way. Reads go
 * through the owning modules' read services rather than their repositories, so this module does not
 * reach across into another aggregate's persistence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesMisService {

  /** Guards against an unbounded report; a wide date range on a busy shop is otherwise unbounded. */
  private static final int MAX_ROWS = 2000;

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

  /** Ledger-affecting credit entries; other entry types are not money movements against a customer. */
  private static final List<CreditEntryType> LEDGER_ENTRY_TYPES =
      List.of(CreditEntryType.SETTLEMENT, CreditEntryType.CHARGE, CreditEntryType.ADJUSTMENT);

  /**
   * Charges auto-raised by a credit sale already appear as the sale row itself; including them would
   * double-count the receivable.
   */
  private static final String AUTO_SALE_CHARGE_PREFIX = "SALE:CREDIT:";

  private static final List<TabularReport.Column> REPORT_COLUMNS =
      List.of(
          TabularReport.Column.date("Date"),
          TabularReport.Column.text("Customer"),
          TabularReport.Column.text("Txn ID"),
          TabularReport.Column.text("Transaction"),
          TabularReport.Column.text("Invoice"),
          TabularReport.Column.money("Bill Amount"),
          TabularReport.Column.money("Cash"),
          TabularReport.Column.money("Online"),
          TabularReport.Column.money("Credit"),
          TabularReport.Column.money("Outstanding"));

  private static final List<TabularReport.Column> DAILY_REPORT_COLUMNS =
      List.of(
          TabularReport.Column.date("Date"),
          TabularReport.Column.money("Total Sale"),
          TabularReport.Column.money("Online"),
          TabularReport.Column.money("Cash"),
          TabularReport.Column.money("Credit"),
          TabularReport.Column.money("MTD"));

  private final SalesLedgerReadService salesLedger;
  private final CustomerLedgerReadService customerCreditLedger;
  private final CustomerDirectoryService customerDirectory;
  private final MISReportService misReportService;
  private final SalesMisMapper mapper;

  /** Binary download payload (bytes + suggested attachment filename). */
  public record ExportFile(byte[] content, String filename) {}

  /** Everything one report run needs, resolved once. */
  private record ReportQuery(
      String shopId,
      LocalDate from,
      LocalDate to,
      String customerId,
      Set<SalesMisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String search) {

    Instant fromInstant() {
      return startOfDay(from);
    }

    Instant toInstantInclusive() {
      return endOfDayInclusive(to);
    }

    boolean matchesCustomer(String candidateCustomerId) {
      return !StringUtils.hasText(customerId) || customerId.equals(candidateCustomerId);
    }
  }

  // ---------------------------------------------------------------------------
  // Entry points
  // ---------------------------------------------------------------------------

  public ExportFile exportExcel(
      String shopId,
      LocalDate from,
      LocalDate to,
      String customerId,
      Set<SalesMisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q,
      SalesMisExportScope scope) {
    SalesMisResponse report = getSalesMis(shopId, from, to, customerId, txnTypes, moneyFilter, q);
    return new ExportFile(
        misReportService.renderExcel(toTabularReport(report, "Sales MIS", scope)),
        exportFilename(report, scope, "xlsx"));
  }

  public ExportFile exportPdf(
      String shopId,
      LocalDate from,
      LocalDate to,
      String customerId,
      Set<SalesMisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q,
      SalesMisExportScope scope) {
    SalesMisResponse report = getSalesMis(shopId, from, to, customerId, txnTypes, moneyFilter, q);
    return new ExportFile(
        misReportService.renderPdf(toTabularReport(report, "Shop", scope)),
        exportFilename(report, scope, "pdf"));
  }

  public SalesMisResponse getSalesMis(
      String shopId,
      LocalDate from,
      LocalDate to,
      String customerId,
      Set<SalesMisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q) {
    ReportQuery query = resolveQuery(shopId, from, to, customerId, txnTypes, moneyFilter, q);

    // Sales are indexed once: rows, returns' against-references and opening balances all need them,
    // and a return can point at a sale from outside the reporting window.
    Map<String, Purchase> saleIndex = loadSaleIndex(query);
    List<Refund> refunds = loadRefundsInWindow(query, saleIndex);
    List<CreditEntry> creditEntries = loadCreditEntriesInWindow(query);

    // Names resolved in one batch once every customer referenced by the report is known.
    Map<String, String> customerNames =
        resolveCustomerNames(referencedCustomerIds(saleIndex, refunds, creditEntries));

    List<SalesMisRowDto> events = new ArrayList<>();
    events.addAll(buildSaleRows(query, saleIndex, customerNames));
    events.addAll(buildReturnRows(query, refunds, saleIndex, customerNames));
    events.addAll(buildCreditRows(creditEntries, query, customerNames));

    Map<String, BigDecimal> openingByCustomer = computeOpeningBalances(query, saleIndex);

    List<SalesMisRowDto> filtered = sortChronologically(applyFilters(events, query));
    List<SalesMisRowDto> rows =
        capRows(withRunningBalances(filtered, openingByCustomer, customerNames));

    return SalesMisResponse.builder()
        .from(query.from())
        .to(query.to())
        .dailyRows(buildDailyRows(rows))
        .rows(rows)
        .summary(buildSummary(rows, openingByCustomer, customerNames))
        .build();
  }

  // ---------------------------------------------------------------------------
  // Day-wise trading summary
  // ---------------------------------------------------------------------------

  /**
   * Collapses the ledger into one row per trading day, carrying a month-to-date running total.
   *
   * <p>Built from the same filtered rows the ledger shows, so the summary always reconciles with
   * the table beneath it. Only sales and sales returns count — a receipt collects against an
   * earlier sale rather than making a new one, so including it would book that sale twice. Return
   * amounts are already negative, which is what nets the day down.
   *
   * <p>Days with no sales activity are omitted rather than emitted as zero rows: a shop that was
   * shut has nothing to report for that date.
   */
  private List<SalesMisDailyRowDto> buildDailyRows(List<SalesMisRowDto> rows) {
    Map<LocalDate, DailyTotals> byDay = new TreeMap<>();

    for (SalesMisRowDto row : rows) {
      if (row.isOpening() || row.getTxnDate() == null || !countsTowardSalesTotal(row)) {
        continue;
      }
      byDay.computeIfAbsent(row.getTxnDate(), day -> new DailyTotals()).add(row);
    }

    List<SalesMisDailyRowDto> daily = new ArrayList<>(byDay.size());
    YearMonth runningMonth = null;
    BigDecimal monthToDate = zeroMoney();

    for (Map.Entry<LocalDate, DailyTotals> entry : byDay.entrySet()) {
      LocalDate day = entry.getKey();
      DailyTotals totals = entry.getValue();

      YearMonth month = YearMonth.from(day);
      if (!month.equals(runningMonth)) {
        runningMonth = month;
        monthToDate = zeroMoney();
      }
      monthToDate = toMoneyScale(monthToDate.add(totals.total));

      daily.add(
          mapper.toDailyRow(
              day, totals.total, totals.cash, totals.online, totals.credit, monthToDate));
    }
    return daily;
  }

  private boolean countsTowardSalesTotal(SalesMisRowDto row) {
    return SalesMisTxnType.parse(row.getTxnType())
        .map(SalesMisTxnType::countsTowardSalesTotal)
        .orElse(false);
  }

  /** Mutable accumulator for one day's legs; totals are scaled once on the way out. */
  private static final class DailyTotals {
    private BigDecimal total = zeroMoney();
    private BigDecimal cash = zeroMoney();
    private BigDecimal online = zeroMoney();
    private BigDecimal credit = zeroMoney();

    void add(SalesMisRowDto row) {
      total = total.add(zeroIfNull(row.getTotalAmount()));
      cash = cash.add(zeroIfNull(row.getCashAmount()));
      online = online.add(zeroIfNull(row.getOnlineAmount()));
      credit = credit.add(zeroIfNull(row.getCreditAmount()));
    }
  }

  // ---------------------------------------------------------------------------
  // Query setup
  // ---------------------------------------------------------------------------

  /** Defaults an open-ended range to month-to-date. */
  private ReportQuery resolveQuery(
      String shopId,
      LocalDate from,
      LocalDate to,
      String customerId,
      Set<SalesMisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q) {
    LocalDate rangeTo = to != null ? to : LocalDate.now(SalesMisUtils.SHOP_ZONE);
    LocalDate rangeFrom = from != null ? from : rangeTo.withDayOfMonth(1);
    return new ReportQuery(
        shopId,
        rangeFrom,
        rangeTo,
        customerId,
        knownTxnTypes(txnTypes),
        moneyFilter != null ? moneyFilter : MoneyFilter.ALL,
        q);
  }

  /**
   * Drops nulls left by unrecognised {@code txnTypes} tokens.
   *
   * <p>The query-parameter converter maps an unknown type to null rather than rejecting the whole
   * request, so a call naming one known and one stale type still filters on the known one.
   */
  private Set<SalesMisTxnType> knownTxnTypes(Set<SalesMisTxnType> txnTypes) {
    if (txnTypes == null || txnTypes.isEmpty()) {
      return Set.of();
    }
    Set<SalesMisTxnType> known = new HashSet<>(txnTypes);
    known.remove(null);
    return known;
  }

  // ---------------------------------------------------------------------------
  // Event loading
  // ---------------------------------------------------------------------------

  /**
   * Completed sales keyed by id: those sold in the window, and all earlier ones.
   *
   * <p>The prior-history load is what makes opening balances and cross-period return links
   * possible.
   */
  private Map<String, Purchase> loadSaleIndex(ReportQuery query) {
    Map<String, Purchase> index = new LinkedHashMap<>();
    indexSales(
        index,
        salesLedger.findCompletedSalesBySoldAt(
            query.shopId(), query.fromInstant(), query.toInstantInclusive()));
    indexSales(
        index,
        salesLedger.findCompletedSalesBySoldAt(
            query.shopId(), Instant.EPOCH, query.fromInstant().minusNanos(1)));
    return index;
  }

  private void indexSales(Map<String, Purchase> index, List<Purchase> sales) {
    for (Purchase sale : sales) {
      if (sale.getId() != null) {
        index.putIfAbsent(sale.getId(), sale);
      }
    }
  }

  private List<SalesMisRowDto> buildSaleRows(
      ReportQuery query, Map<String, Purchase> saleIndex, Map<String, String> customerNames) {
    List<SalesMisRowDto> rows = new ArrayList<>();
    for (Purchase sale : saleIndex.values()) {
      LocalDate day = toShopDate(SalesMisMapper.effectiveSaleInstant(sale));
      if (!isWithin(day, query.from(), query.to())
          || !query.matchesCustomer(SalesMisMapper.customerKeyOf(sale))) {
        continue;
      }
      rows.add(mapper.toSaleRow(sale, tenderFor(sale), customerNames));
    }
    return rows;
  }

  /**
   * Returns posted in the window, with the sales they were raised against pulled into the index.
   *
   * <p>Linking happens during loading so the sale index is complete before customer names are
   * resolved — a walk-in refund's customer is only known through its sale.
   */
  private List<Refund> loadRefundsInWindow(ReportQuery query, Map<String, Purchase> saleIndex) {
    List<Refund> inWindow = new ArrayList<>();
    for (Refund refund :
        salesLedger.findRefundsByCreatedAt(
            query.shopId(), query.fromInstant(), query.toInstantInclusive())) {
      if (!isWithin(toShopDate(refund.getCreatedAt()), query.from(), query.to())) {
        continue;
      }
      resolveLinkedSale(refund, saleIndex);
      inWindow.add(refund);
    }
    return inWindow;
  }

  private List<SalesMisRowDto> buildReturnRows(
      ReportQuery query,
      List<Refund> refunds,
      Map<String, Purchase> saleIndex,
      Map<String, String> customerNames) {
    List<SalesMisRowDto> rows = new ArrayList<>();
    for (Refund refund : refunds) {
      Purchase linked = linkedSaleOf(refund, saleIndex);
      if (!query.matchesCustomer(SalesMisMapper.customerKeyOf(refund, linked))) {
        continue;
      }
      rows.add(mapper.toSalesReturnRow(refund, linked, customerNames));
    }
    return rows;
  }

  private Purchase linkedSaleOf(Refund refund, Map<String, Purchase> saleIndex) {
    return refund.getPurchaseId() != null ? saleIndex.get(refund.getPurchaseId()) : null;
  }

  /** Every customer the report will reference, so names can be fetched in one round trip. */
  private Set<String> referencedCustomerIds(
      Map<String, Purchase> saleIndex, List<Refund> refunds, List<CreditEntry> creditEntries) {
    Set<String> ids = new HashSet<>();
    saleIndex.values().forEach(sale -> ids.add(SalesMisMapper.customerKeyOf(sale)));
    refunds.forEach(
        refund -> ids.add(SalesMisMapper.customerKeyOf(refund, linkedSaleOf(refund, saleIndex))));
    creditEntries.forEach(entry -> ids.add(entry.getPartyRefId()));
    ids.remove(null);
    return ids;
  }

  /**
   * Display names for the referenced customers.
   *
   * <p>The walk-in bucket is labelled here rather than looked up: it is a reporting construct, not a
   * customer record.
   */
  private Map<String, String> resolveCustomerNames(Set<String> customerIds) {
    Set<String> lookupIds = new HashSet<>(customerIds);
    boolean hasWalkIn = lookupIds.remove(SalesMisMapper.WALK_IN_CUSTOMER_ID);

    Map<String, String> names = new HashMap<>(customerDirectory.namesByIds(lookupIds));
    if (hasWalkIn) {
      names.put(SalesMisMapper.WALK_IN_CUSTOMER_ID, SalesMisMapper.WALK_IN_CUSTOMER_NAME);
    }
    return names;
  }

  /** The sale a return was raised against, fetching it if it falls outside the loaded window. */
  private Purchase resolveLinkedSale(Refund refund, Map<String, Purchase> saleIndex) {
    String saleId = refund.getPurchaseId();
    if (!StringUtils.hasText(saleId)) {
      return null;
    }
    Purchase linked = saleIndex.get(saleId);
    if (linked != null) {
      return linked;
    }
    linked = salesLedger.findSaleById(saleId).orElse(null);
    if (linked != null && linked.getId() != null) {
      saleIndex.put(linked.getId(), linked);
    }
    return linked;
  }

  /**
   * Ledger-affecting customer credit entries dated or posted inside the window.
   *
   * <p>Queried two ways and de-duplicated by id: an entry with no {@code txnDate}, or one back-dated
   * outside the window, is only reachable by posting time.
   */
  private List<CreditEntry> loadCreditEntriesInWindow(ReportQuery query) {
    Map<String, CreditEntry> byId = new LinkedHashMap<>();

    for (CreditEntry entry :
        customerCreditLedger.findCustomerEntriesByTxnDate(
            query.shopId(), LEDGER_ENTRY_TYPES, query.from(), query.to())) {
      if (entry.getId() != null) {
        byId.put(entry.getId(), entry);
      }
    }

    for (CreditEntry entry :
        customerCreditLedger.findCustomerEntriesByCreatedAt(
            query.shopId(), query.fromInstant(), query.toInstantInclusive())) {
      if (entry.getId() == null || !LEDGER_ENTRY_TYPES.contains(entry.getEntryType())) {
        continue;
      }
      if (!isWithin(effectiveCreditDate(entry), query.from(), query.to())) {
        continue;
      }
      byId.putIfAbsent(entry.getId(), entry);
    }
    return new ArrayList<>(byId.values());
  }

  private List<SalesMisRowDto> buildCreditRows(
      List<CreditEntry> creditEntries, ReportQuery query, Map<String, String> customerNames) {
    List<SalesMisRowDto> rows = new ArrayList<>();
    for (CreditEntry entry : creditEntries) {
      if (!query.matchesCustomer(entry.getPartyRefId()) || isAutoSaleCharge(entry)) {
        continue;
      }
      rows.add(mapper.toCreditRow(entry, customerNames));
    }
    return rows;
  }

  private LocalDate effectiveCreditDate(CreditEntry entry) {
    return entry.getTxnDate() != null ? entry.getTxnDate() : toShopDate(entry.getCreatedAt());
  }

  private boolean isAutoSaleCharge(CreditEntry entry) {
    return entry.getEntryType() == CreditEntryType.CHARGE
        && entry.getSourceKey() != null
        && entry.getSourceKey().startsWith(AUTO_SALE_CHARGE_PREFIX);
  }

  // ---------------------------------------------------------------------------
  // Opening balances
  // ---------------------------------------------------------------------------

  /** Net receivable per customer carried in from before the window. */
  private Map<String, BigDecimal> computeOpeningBalances(
      ReportQuery query, Map<String, Purchase> saleIndex) {
    Map<String, BigDecimal> opening = new HashMap<>();
    addPriorSales(opening, query, saleIndex);
    addPriorReturns(opening, query, saleIndex);
    addPriorCreditEntries(opening, query);
    return opening;
  }

  private void addPriorSales(
      Map<String, BigDecimal> opening, ReportQuery query, Map<String, Purchase> saleIndex) {
    for (Purchase sale : saleIndex.values()) {
      LocalDate day = toShopDate(SalesMisMapper.effectiveSaleInstant(sale));
      String customerId = SalesMisMapper.customerKeyOf(sale);
      if (day == null || !day.isBefore(query.from()) || !query.matchesCustomer(customerId)) {
        continue;
      }
      // Only the unpaid leg carries forward; cash and online were settled at the time.
      addDelta(opening, customerId, tenderFor(sale).creditAmount());
    }
  }

  private void addPriorReturns(
      Map<String, BigDecimal> opening, ReportQuery query, Map<String, Purchase> saleIndex) {
    List<Refund> priorRefunds =
        salesLedger.findRefundsByCreatedAt(
            query.shopId(), Instant.EPOCH, query.fromInstant().minusNanos(1));

    for (Refund refund : priorRefunds) {
      String customerId = SalesMisMapper.customerKeyOf(refund, linkedSaleOf(refund, saleIndex));
      if (!StringUtils.hasText(customerId) || !query.matchesCustomer(customerId)) {
        continue;
      }
      addDelta(opening, customerId, returnCreditLeg(refund).negate());
    }
  }

  /** A return with no refund split recorded is treated as wholly reducing credit. */
  private BigDecimal returnCreditLeg(Refund refund) {
    BigDecimal creditLeg = zeroIfNull(refund.getRefundToCredit());
    if (creditLeg.signum() == 0 && hasNoRecordedRefund(refund)) {
      return zeroIfNull(refund.getRefundAmount());
    }
    return creditLeg;
  }

  private boolean hasNoRecordedRefund(Refund refund) {
    return zeroIfNull(refund.getRefundCash()).signum() == 0
        && zeroIfNull(refund.getRefundOnline()).signum() == 0
        && zeroIfNull(refund.getRefundToCredit()).signum() == 0;
  }

  private void addPriorCreditEntries(Map<String, BigDecimal> opening, ReportQuery query) {
    List<CreditEntry> priorEntries =
        customerCreditLedger.findCustomerEntriesByTxnDate(
            query.shopId(), null, LocalDate.EPOCH, query.from().minusDays(1));

    for (CreditEntry entry : priorEntries) {
      if (!query.matchesCustomer(entry.getPartyRefId()) || isAutoSaleCharge(entry)) {
        continue;
      }
      BigDecimal amount = zeroIfNull(entry.getAmount());
      boolean reducesReceivable =
          entry.getEntryType() == CreditEntryType.SETTLEMENT
              || entry.getEntryType() == CreditEntryType.RETURN;
      addDelta(opening, entry.getPartyRefId(), reducesReceivable ? amount.negate() : amount);
    }
  }

  // ---------------------------------------------------------------------------
  // Filtering, ordering, balances
  // ---------------------------------------------------------------------------

  private List<SalesMisRowDto> applyFilters(List<SalesMisRowDto> events, ReportQuery query) {
    return events.stream()
        .filter(row -> matchesTxnType(row, query.txnTypes()))
        .filter(row -> matchesMoneyFilter(row, query.moneyFilter()))
        .filter(row -> matchesSearch(row, query.search()))
        .toList();
  }

  private boolean matchesTxnType(SalesMisRowDto row, Set<SalesMisTxnType> txnTypes) {
    if (txnTypes.isEmpty()) {
      return true;
    }
    return SalesMisTxnType.parse(row.getTxnType()).map(txnTypes::contains).orElse(false);
  }

  private boolean matchesMoneyFilter(SalesMisRowDto row, MoneyFilter filter) {
    return switch (filter) {
      case ALL -> true;
      case HAS_CASH -> isNonZero(row.getCashAmount());
      case HAS_ONLINE -> isNonZero(row.getOnlineAmount());
      case HAS_CREDIT -> isNonZero(row.getCreditAmount());
      case FULLY_PAID -> !isNonZero(row.getCreditAmount()) && isNonZero(row.getTotalAmount());
      case MIXED -> countNonZeroLegs(row) > 1;
    };
  }

  private int countNonZeroLegs(SalesMisRowDto row) {
    int legs = 0;
    if (isNonZero(row.getCashAmount())) legs++;
    if (isNonZero(row.getOnlineAmount())) legs++;
    if (isNonZero(row.getCreditAmount())) legs++;
    return legs;
  }

  private boolean matchesSearch(SalesMisRowDto row, String search) {
    if (!StringUtils.hasText(search)) {
      return true;
    }
    String needle = lowerOrEmpty(search.trim());
    return containsIgnoreCase(row.getCustomerName(), needle)
        || containsIgnoreCase(row.getRefNo(), needle)
        || containsIgnoreCase(row.getTxnId(), needle)
        || containsIgnoreCase(row.getAgainstRefNo(), needle);
  }

  private List<SalesMisRowDto> sortChronologically(List<SalesMisRowDto> events) {
    List<SalesMisRowDto> sorted = new ArrayList<>(events);
    sorted.sort(
        Comparator.comparing(
                SalesMisRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                SalesMisRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(row -> lowerOrEmpty(row.getCustomerName())));
    return sorted;
  }

  /**
   * Inserts each customer's opening row and stamps a running receivable on every row.
   *
   * <p>The opening row is emitted lazily at a customer's first event rather than up front, so it
   * sits immediately above the rows it explains.
   */
  private List<SalesMisRowDto> withRunningBalances(
      List<SalesMisRowDto> events,
      Map<String, BigDecimal> openingByCustomer,
      Map<String, String> customerNames) {
    Map<String, BigDecimal> running = new HashMap<>();
    openingByCustomer.forEach((customerId, amount) -> running.put(customerId, toMoneyScale(amount)));

    List<SalesMisRowDto> out = new ArrayList<>();
    Set<String> seenCustomers = new HashSet<>();

    for (SalesMisRowDto row : events) {
      String customerId = row.getCustomerId() != null ? row.getCustomerId() : "";

      if (seenCustomers.add(customerId) && hasOpeningBalance(openingByCustomer, customerId)) {
        BigDecimal opening = toMoneyScale(openingByCustomer.get(customerId));
        running.put(customerId, opening);
        out.add(
            mapper.toOpeningRow(
                customerId, opening, row.getTxnDate(), row.getCustomerName(), customerNames));
      }

      BigDecimal balance =
          toMoneyScale(running.getOrDefault(customerId, zeroMoney()).add(receivableDelta(row)));
      running.put(customerId, balance);
      row.setBalanceAfter(balance);
      out.add(row);
    }
    return out;
  }

  private boolean hasOpeningBalance(Map<String, BigDecimal> openingByCustomer, String customerId) {
    BigDecimal opening = openingByCustomer.get(customerId);
    return opening != null && opening.signum() != 0;
  }

  /** How much a row moves the receivable: positive is owed more, negative is owed less. */
  private BigDecimal receivableDelta(SalesMisRowDto row) {
    SalesMisTxnType type = SalesMisTxnType.parse(row.getTxnType()).orElse(null);
    if (type == null) {
      return zeroMoney();
    }
    if (type.increasesReceivable()) {
      return zeroIfNull(row.getCreditAmount());
    }
    return switch (type) {
      case CUSTOMER_RECEIPT -> receiptDelta(row);
      case SALES_RETURN -> returnDelta(row);
      default -> zeroMoney();
    };
  }

  /** A receipt reduces the receivable by whatever actually moved. */
  private BigDecimal receiptDelta(SalesMisRowDto row) {
    BigDecimal received = zeroIfNull(row.getCashAmount()).add(zeroIfNull(row.getOnlineAmount()));
    if (received.signum() == 0) {
      received = zeroIfNull(row.getTotalAmount()).abs();
    }
    return received.negate();
  }

  /**
   * A return reduces the receivable by its credit leg.
   *
   * <p>When no credit leg was recorded the total is used as-is — return totals are already negative,
   * so it is a reduction either way.
   */
  private BigDecimal returnDelta(SalesMisRowDto row) {
    BigDecimal credit = zeroIfNull(row.getCreditAmount()).abs();
    return credit.signum() == 0 ? zeroIfNull(row.getTotalAmount()) : credit.negate();
  }

  private List<SalesMisRowDto> capRows(List<SalesMisRowDto> rows) {
    if (rows.size() <= MAX_ROWS) {
      return rows;
    }
    log.info("Sales money MIS truncated from {} to {} rows", rows.size(), MAX_ROWS);
    return new ArrayList<>(rows.subList(0, MAX_ROWS));
  }

  // ---------------------------------------------------------------------------
  // Summary
  // ---------------------------------------------------------------------------

  private SalesMisSummaryDto buildSummary(
      List<SalesMisRowDto> rows,
      Map<String, BigDecimal> openingByCustomer,
      Map<String, String> customerNames) {
    BigDecimal cash = zeroMoney();
    BigDecimal online = zeroMoney();
    BigDecimal credit = zeroMoney();
    BigDecimal sales = zeroMoney();
    Map<String, SalesMisRowDto> lastRowByCustomer = new LinkedHashMap<>();

    for (SalesMisRowDto row : rows) {
      if (row.isOpening()) {
        continue;
      }
      cash = cash.add(zeroIfNull(row.getCashAmount()));
      online = online.add(zeroIfNull(row.getOnlineAmount()));
      credit = credit.add(zeroIfNull(row.getCreditAmount()));
      if (SalesMisTxnType.SALE.name().equals(row.getTxnType())) {
        sales = sales.add(zeroIfNull(row.getTotalAmount()));
      }
      if (row.getCustomerId() != null) {
        lastRowByCustomer.put(row.getCustomerId(), row);
      }
    }

    return SalesMisSummaryDto.builder()
        .openingBalanceTotal(sumOpening(openingByCustomer))
        .periodCashTotal(toMoneyScale(cash))
        .periodOnlineTotal(toMoneyScale(online))
        .periodCreditTotal(toMoneyScale(credit))
        .periodSalesTotal(toMoneyScale(sales))
        .currentReceivableTotal(currentReceivable(openingByCustomer, lastRowByCustomer))
        .customerSummaries(
            customerSummaries(openingByCustomer, lastRowByCustomer, customerNames))
        .build();
  }

  private BigDecimal sumOpening(Map<String, BigDecimal> openingByCustomer) {
    return openingByCustomer.values().stream()
        .map(SalesMisUtils::toMoneyScale)
        .reduce(zeroMoney(), BigDecimal::add);
  }

  /**
   * Closing receivable across customers.
   *
   * <p>Customers with an opening balance but no activity in the window still owe it, so they are
   * added from the opening map rather than dropped for having no rows.
   */
  private BigDecimal currentReceivable(
      Map<String, BigDecimal> openingByCustomer, Map<String, SalesMisRowDto> lastRowByCustomer) {
    BigDecimal total =
        lastRowByCustomer.values().stream()
            .map(row -> zeroIfNull(row.getBalanceAfter()))
            .reduce(zeroMoney(), BigDecimal::add);

    for (Map.Entry<String, BigDecimal> entry : openingByCustomer.entrySet()) {
      if (!lastRowByCustomer.containsKey(entry.getKey())) {
        total = total.add(toMoneyScale(entry.getValue()));
      }
    }
    return toMoneyScale(total);
  }

  private List<SalesMisCustomerSummaryDto> customerSummaries(
      Map<String, BigDecimal> openingByCustomer,
      Map<String, SalesMisRowDto> lastRowByCustomer,
      Map<String, String> customerNames) {
    Set<String> customerIds = new HashSet<>(openingByCustomer.keySet());
    customerIds.addAll(lastRowByCustomer.keySet());

    List<SalesMisCustomerSummaryDto> summaries = new ArrayList<>(customerIds.size());
    for (String customerId : customerIds) {
      BigDecimal opening = toMoneyScale(openingByCustomer.getOrDefault(customerId, zeroMoney()));
      SalesMisRowDto last = lastRowByCustomer.get(customerId);
      BigDecimal closing = last != null ? zeroIfNull(last.getBalanceAfter()) : opening;
      summaries.add(mapper.toCustomerSummary(customerId, opening, closing, customerNames));
    }
    summaries.sort(Comparator.comparing(s -> lowerOrEmpty(s.getCustomerName())));
    return summaries;
  }

  // ---------------------------------------------------------------------------
  // Export shaping
  // ---------------------------------------------------------------------------

  /** Projects the requested table onto the generic model the document service renders. */
  private TabularReport toTabularReport(
      SalesMisResponse report, String shopName, SalesMisExportScope scope) {
    return scope == SalesMisExportScope.DAILY
        ? toDailyTabularReport(report, shopName)
        : toLedgerTabularReport(report, shopName);
  }

  /** The day-wise summary: what the screen shows above the ledger. */
  private TabularReport toDailyTabularReport(SalesMisResponse report, String shopName) {
    List<List<Object>> rows = new ArrayList<>(report.getDailyRows().size());
    BigDecimal totalSale = zeroMoney();
    BigDecimal cash = zeroMoney();
    BigDecimal online = zeroMoney();
    BigDecimal credit = zeroMoney();

    for (SalesMisDailyRowDto day : report.getDailyRows()) {
      rows.add(
          Arrays.asList(
              day.getTxnDate(),
              day.getTotalSale(),
              day.getOnlineAmount(),
              day.getCashAmount(),
              day.getCreditAmount(),
              day.getMonthToDateTotal()));
      totalSale = totalSale.add(zeroIfNull(day.getTotalSale()));
      cash = cash.add(zeroIfNull(day.getCashAmount()));
      online = online.add(zeroIfNull(day.getOnlineAmount()));
      credit = credit.add(zeroIfNull(day.getCreditAmount()));
    }

    // MTD is a running figure, so summing the column would be meaningless — left blank.
    List<Object> totals =
        Arrays.asList(
            "Totals",
            toMoneyScale(totalSale),
            toMoneyScale(online),
            toMoneyScale(cash),
            toMoneyScale(credit),
            null);

    return new TabularReport(
        "Daily Sales",
        (shopName != null ? shopName : "Shop") + " — Daily Sales",
        periodSubtitle(report),
        DAILY_REPORT_COLUMNS,
        rows,
        totals,
        List.of(
            "Total sale is net of sales returns and equals online + cash + credit. "
                + "MTD restarts on the 1st of each month."));
  }

  /** The transaction ledger. */
  private TabularReport toLedgerTabularReport(SalesMisResponse report, String shopName) {
    List<List<Object>> rows = new ArrayList<>(report.getRows().size());
    for (SalesMisRowDto row : report.getRows()) {
      // Arrays.asList rather than List.of: cells are legitimately null and List.of rejects them.
      rows.add(
          Arrays.asList(
              row.getTxnDate(),
              row.getCustomerName(),
              row.getTxnId(),
              row.getTxnTypeLabel(),
              row.getRefNo(),
              row.getTotalAmount(),
              row.getCashAmount(),
              row.getOnlineAmount(),
              row.getCreditAmount(),
              row.getBalanceAfter()));
    }

    return new TabularReport(
        "Sales MIS",
        (shopName != null ? shopName : "Shop") + " — Sales MIS",
        periodSubtitle(report),
        REPORT_COLUMNS,
        rows,
        totalsRow(report.getSummary()),
        summaryNotes(report.getSummary()));
  }

  private String periodSubtitle(SalesMisResponse report) {
    return "Period: "
        + formatDate(report.getFrom())
        + " – "
        + formatDate(report.getTo())
        + " · Timezone Asia/Kolkata";
  }

  private String formatDate(LocalDate date) {
    return date != null ? date.format(DATE_FORMAT) : "";
  }

  private List<Object> totalsRow(SalesMisSummaryDto summary) {
    if (summary == null) {
      return null;
    }
    List<Object> totals = new ArrayList<>(REPORT_COLUMNS.size());
    totals.add("Totals");
    // Blank out Customer / Txn ID / Transaction / Invoice before money columns.
    for (int i = 1; i < 5; i++) {
      totals.add("");
    }
    totals.add(summary.getPeriodSalesTotal());
    totals.add(summary.getPeriodCashTotal());
    totals.add(summary.getPeriodOnlineTotal());
    totals.add(summary.getPeriodCreditTotal());
    totals.add(summary.getCurrentReceivableTotal());
    return totals;
  }

  private List<String> summaryNotes(SalesMisSummaryDto summary) {
    if (summary == null) {
      return List.of();
    }
    return List.of(
        "Summary — Cash: "
            + summary.getPeriodCashTotal()
            + " · Online: "
            + summary.getPeriodOnlineTotal()
            + " · Credit: "
            + summary.getPeriodCreditTotal()
            + " · Current receivable: "
            + summary.getCurrentReceivableTotal());
  }

  private static String exportFilename(
      SalesMisResponse report, SalesMisExportScope scope, String extension) {
    return "sales-mis-"
        + scope.filenameInfix()
        + (report.getFrom() != null ? report.getFrom() : "from")
        + "-"
        + (report.getTo() != null ? report.getTo() : "to")
        + "."
        + extension;
  }

  /** How a sale was tendered — derived by the product module, which owns the sale shape. */
  private VendorPurchasePaymentBreakdown.Result tenderFor(Purchase sale) {
    return salesLedger.tenderFor(sale);
  }
}
