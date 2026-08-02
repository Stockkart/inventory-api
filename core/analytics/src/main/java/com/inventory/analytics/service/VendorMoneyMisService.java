package com.inventory.analytics.service;

import static com.inventory.analytics.utils.VendorMoneyMisUtils.addDelta;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.containsIgnoreCase;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.endOfDayInclusive;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.isNonZero;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.isWithin;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.lowerOrEmpty;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.startOfDay;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.toMoneyScale;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.toShopDate;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.zeroIfNull;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.zeroMoney;

import com.inventory.analytics.mapper.VendorMoneyMisMapper;
import com.inventory.analytics.domain.model.MisTxnType;
import com.inventory.analytics.domain.model.MoneyFilter;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisResponse;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisRowDto;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisSummaryDto;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisVendorSummaryDto;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.service.VendorLedgerReadService;
import com.inventory.documentservice.rest.dto.TabularReport;
import com.inventory.documentservice.service.MISReportService;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.service.VendorPurchaseLedgerReadService;
import com.inventory.product.service.VendorPurchasePaymentBreakdown;
import com.inventory.user.service.VendorDirectoryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Vendor Money MIS: Excel-style row ledger of purchase / payment / return / charge events with
 * cash/online/credit columns and running payable balance per vendor.
 *
 * <p>Reads go through the owning modules' read services rather than their repositories, so this
 * module does not reach across into another aggregate's persistence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorMoneyMisService {

  /** Guards against an unbounded report; a wide date range on a busy shop is otherwise unbounded. */
  private static final int MAX_ROWS = 2000;

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

  /** Ledger-affecting credit entries; other entry types are not money movements against a vendor. */
  private static final List<CreditEntryType> LEDGER_ENTRY_TYPES =
      List.of(CreditEntryType.SETTLEMENT, CreditEntryType.CHARGE, CreditEntryType.ADJUSTMENT);

  /**
   * Charges auto-raised by a credit purchase already appear as the purchase row itself; including
   * them would double-count the payable.
   */
  private static final String AUTO_PURCHASE_CHARGE_PREFIX = "PURCHASE:CREDIT:";

  private static final List<TabularReport.Column> REPORT_COLUMNS =
      List.of(
          TabularReport.Column.date("Date"),
          TabularReport.Column.text("Supplier"),
          TabularReport.Column.text("Txn ID"),
          TabularReport.Column.text("Transaction"),
          TabularReport.Column.text("Invoice"),
          TabularReport.Column.money("Bill Amount"),
          TabularReport.Column.money("Cash"),
          TabularReport.Column.money("Online"),
          TabularReport.Column.money("Credit"),
          TabularReport.Column.money("Outstanding"));

  private final VendorPurchaseLedgerReadService vendorPurchaseLedger;
  private final VendorLedgerReadService vendorCreditLedger;
  private final VendorDirectoryService vendorDirectory;
  private final MISReportService misReportService;
  private final VendorMoneyMisMapper mapper;

  /** Binary download payload (bytes + suggested attachment filename). */
  public record ExportFile(byte[] content, String filename) {}

  /** Everything one report run needs, resolved once. */
  private record ReportQuery(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      Set<MisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String search) {

    Instant fromInstant() {
      return startOfDay(from);
    }

    Instant toInstantInclusive() {
      return endOfDayInclusive(to);
    }

    boolean matchesVendor(String candidateVendorId) {
      return !StringUtils.hasText(vendorId) || vendorId.equals(candidateVendorId);
    }
  }

  // ---------------------------------------------------------------------------
  // Entry points
  // ---------------------------------------------------------------------------

  public ExportFile exportExcel(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      Set<MisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q) {
    VendorMoneyMisResponse report =
        getVendorMis(shopId, from, to, vendorId, txnTypes, moneyFilter, q);
    return new ExportFile(
        misReportService.renderExcel(toTabularReport(report, "Vendor Money MIS")),
        exportFilename(report, "xlsx"));
  }

  public ExportFile exportPdf(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      Set<MisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q) {
    VendorMoneyMisResponse report =
        getVendorMis(shopId, from, to, vendorId, txnTypes, moneyFilter, q);
    return new ExportFile(
        misReportService.renderPdf(toTabularReport(report, "Shop")),
        exportFilename(report, "pdf"));
  }

  public VendorMoneyMisResponse getVendorMis(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      Set<MisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q) {
    ReportQuery query = resolveQuery(shopId, from, to, vendorId, txnTypes, moneyFilter, q);

    // Invoices are indexed once: rows, returns' against-references and opening balances all need
    // them, and a return can point at an invoice from outside the reporting window.
    Map<String, VendorPurchaseInvoice> invoiceIndex = loadInvoiceIndex(query);
    List<VendorPurchaseReturn> returns = loadReturnsInWindow(query, invoiceIndex);
    List<CreditEntry> creditEntries = loadCreditEntriesInWindow(query);

    // Names resolved in one batch once every vendor referenced by the report is known.
    Map<String, String> vendorNames =
        vendorDirectory.namesByIds(referencedVendorIds(invoiceIndex, returns, creditEntries));

    List<VendorMoneyMisRowDto> events = new ArrayList<>();
    events.addAll(buildPurchaseRows(query, invoiceIndex, vendorNames));
    events.addAll(buildReturnRows(query, returns, invoiceIndex, vendorNames));
    events.addAll(buildCreditRows(creditEntries, query, vendorNames));

    Map<String, BigDecimal> openingByVendor = computeOpeningBalances(query, invoiceIndex);

    List<VendorMoneyMisRowDto> filtered = sortChronologically(applyFilters(events, query));
    List<VendorMoneyMisRowDto> rows =
        capRows(withRunningBalances(filtered, openingByVendor, vendorNames));

    return VendorMoneyMisResponse.builder()
        .from(query.from())
        .to(query.to())
        .rows(rows)
        .summary(buildSummary(rows, openingByVendor, vendorNames))
        .build();
  }

  // ---------------------------------------------------------------------------
  // Query setup
  // ---------------------------------------------------------------------------

  /** Defaults an open-ended range to month-to-date. */
  private ReportQuery resolveQuery(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      Set<MisTxnType> txnTypes,
      MoneyFilter moneyFilter,
      String q) {
    LocalDate rangeTo = to != null ? to : LocalDate.now(com.inventory.analytics.utils.VendorMoneyMisUtils.SHOP_ZONE);
    LocalDate rangeFrom = from != null ? from : rangeTo.withDayOfMonth(1);
    return new ReportQuery(
        shopId,
        rangeFrom,
        rangeTo,
        vendorId,
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
  private Set<MisTxnType> knownTxnTypes(Set<MisTxnType> txnTypes) {
    if (txnTypes == null || txnTypes.isEmpty()) {
      return Set.of();
    }
    Set<MisTxnType> known = new HashSet<>(txnTypes);
    known.remove(null);
    return known;
  }

  // ---------------------------------------------------------------------------
  // Event loading
  // ---------------------------------------------------------------------------

  /**
   * Invoices keyed by id: those dated in the window, those captured in it, and all earlier ones.
   *
   * <p>Both date fields are queried because {@code invoiceDate} is optional on older records, and
   * the prior-history load is what makes opening balances and cross-period return links possible.
   */
  private Map<String, VendorPurchaseInvoice> loadInvoiceIndex(ReportQuery query) {
    Map<String, VendorPurchaseInvoice> index = new LinkedHashMap<>();
    indexInvoices(
        index,
        vendorPurchaseLedger.findInvoicesByInvoiceDate(
            query.shopId(), query.fromInstant(), query.toInstantInclusive()));
    indexInvoices(
        index,
        vendorPurchaseLedger.findInvoicesByCreatedAt(
            query.shopId(), query.fromInstant(), query.toInstantInclusive()));
    indexInvoices(
        index,
        vendorPurchaseLedger.findInvoicesByCreatedAt(
            query.shopId(), Instant.EPOCH, query.fromInstant().minusNanos(1)));
    return index;
  }

  private void indexInvoices(
      Map<String, VendorPurchaseInvoice> index, List<VendorPurchaseInvoice> invoices) {
    for (VendorPurchaseInvoice invoice : invoices) {
      if (invoice.getId() != null) {
        index.putIfAbsent(invoice.getId(), invoice);
      }
    }
  }

  private List<VendorMoneyMisRowDto> buildPurchaseRows(
      ReportQuery query,
      Map<String, VendorPurchaseInvoice> invoiceIndex,
      Map<String, String> vendorNames) {
    List<VendorMoneyMisRowDto> rows = new ArrayList<>();
    for (VendorPurchaseInvoice invoice : invoiceIndex.values()) {
      LocalDate day = toShopDate(VendorMoneyMisMapper.effectiveInvoiceInstant(invoice));
      if (!isWithin(day, query.from(), query.to()) || !query.matchesVendor(invoice.getVendorId())) {
        continue;
      }
      rows.add(mapper.toPurchaseRow(invoice, tenderFor(invoice), vendorNames));
    }
    return rows;
  }

  /**
   * Returns posted in the window, with their originating invoices pulled into the index.
   *
   * <p>Linking happens during loading so the invoice index is complete before vendor names are
   * resolved — a return's vendor is only known through its invoice.
   */
  private List<VendorPurchaseReturn> loadReturnsInWindow(
      ReportQuery query, Map<String, VendorPurchaseInvoice> invoiceIndex) {
    List<VendorPurchaseReturn> inWindow = new ArrayList<>();
    for (VendorPurchaseReturn ret :
        vendorPurchaseLedger.findReturnsByCreatedAt(
            query.shopId(), query.fromInstant(), query.toInstantInclusive())) {
      if (!isWithin(toShopDate(ret.getCreatedAt()), query.from(), query.to())) {
        continue;
      }
      resolveLinkedInvoice(ret, invoiceIndex);
      inWindow.add(ret);
    }
    return inWindow;
  }

  private List<VendorMoneyMisRowDto> buildReturnRows(
      ReportQuery query,
      List<VendorPurchaseReturn> returns,
      Map<String, VendorPurchaseInvoice> invoiceIndex,
      Map<String, String> vendorNames) {
    List<VendorMoneyMisRowDto> rows = new ArrayList<>();
    for (VendorPurchaseReturn ret : returns) {
      VendorPurchaseInvoice linked = linkedInvoiceOf(ret, invoiceIndex);
      if (!query.matchesVendor(linked != null ? linked.getVendorId() : null)) {
        continue;
      }
      rows.add(mapper.toReturnRow(ret, linked, vendorNames));
    }
    return rows;
  }

  private VendorPurchaseInvoice linkedInvoiceOf(
      VendorPurchaseReturn ret, Map<String, VendorPurchaseInvoice> invoiceIndex) {
    return ret.getVendorPurchaseInvoiceId() != null
        ? invoiceIndex.get(ret.getVendorPurchaseInvoiceId())
        : null;
  }

  /** Every vendor the report will reference, so names can be fetched in one round trip. */
  private Set<String> referencedVendorIds(
      Map<String, VendorPurchaseInvoice> invoiceIndex,
      List<VendorPurchaseReturn> returns,
      List<CreditEntry> creditEntries) {
    Set<String> ids = new HashSet<>();
    invoiceIndex.values().forEach(invoice -> ids.add(invoice.getVendorId()));
    returns.forEach(
        ret -> {
          VendorPurchaseInvoice linked = linkedInvoiceOf(ret, invoiceIndex);
          if (linked != null) {
            ids.add(linked.getVendorId());
          }
        });
    creditEntries.forEach(entry -> ids.add(entry.getPartyRefId()));
    ids.remove(null);
    return ids;
  }

  /** The invoice a return was raised against, fetching it if it falls outside the loaded window. */
  private VendorPurchaseInvoice resolveLinkedInvoice(
      VendorPurchaseReturn ret, Map<String, VendorPurchaseInvoice> invoiceIndex) {
    String invoiceId = ret.getVendorPurchaseInvoiceId();
    if (!StringUtils.hasText(invoiceId)) {
      return null;
    }
    VendorPurchaseInvoice linked = invoiceIndex.get(invoiceId);
    if (linked != null) {
      return linked;
    }
    linked = vendorPurchaseLedger.findInvoiceById(invoiceId).orElse(null);
    if (linked != null) {
      invoiceIndex.put(linked.getId(), linked);
    }
    return linked;
  }

  /**
   * Ledger-affecting vendor credit entries dated or posted inside the window.
   *
   * <p>Queried two ways and de-duplicated by id: an entry with no {@code txnDate}, or one
   * back-dated outside the window, is only reachable by posting time.
   */
  private List<CreditEntry> loadCreditEntriesInWindow(ReportQuery query) {
    Map<String, CreditEntry> byId = new LinkedHashMap<>();

    for (CreditEntry entry :
        vendorCreditLedger.findVendorEntriesByTxnDate(
            query.shopId(), LEDGER_ENTRY_TYPES, query.from(), query.to())) {
      if (entry.getId() != null) {
        byId.put(entry.getId(), entry);
      }
    }

    for (CreditEntry entry :
        vendorCreditLedger.findVendorEntriesByCreatedAt(
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

  private List<VendorMoneyMisRowDto> buildCreditRows(
      List<CreditEntry> creditEntries, ReportQuery query, Map<String, String> vendorNames) {
    List<VendorMoneyMisRowDto> rows = new ArrayList<>();
    for (CreditEntry entry : creditEntries) {
      if (!query.matchesVendor(entry.getPartyRefId()) || isAutoPurchaseCharge(entry)) {
        continue;
      }
      rows.add(mapper.toCreditRow(entry, vendorNames));
    }
    return rows;
  }

  private LocalDate effectiveCreditDate(CreditEntry entry) {
    return entry.getTxnDate() != null ? entry.getTxnDate() : toShopDate(entry.getCreatedAt());
  }

  private boolean isAutoPurchaseCharge(CreditEntry entry) {
    return entry.getEntryType() == CreditEntryType.CHARGE
        && entry.getSourceKey() != null
        && entry.getSourceKey().startsWith(AUTO_PURCHASE_CHARGE_PREFIX);
  }

  // ---------------------------------------------------------------------------
  // Opening balances
  // ---------------------------------------------------------------------------

  /** Net payable per vendor carried in from before the window. */
  private Map<String, BigDecimal> computeOpeningBalances(
      ReportQuery query, Map<String, VendorPurchaseInvoice> invoiceIndex) {
    Map<String, BigDecimal> opening = new HashMap<>();
    addPriorPurchases(opening, query, invoiceIndex);
    addPriorReturns(opening, query, invoiceIndex);
    addPriorCreditEntries(opening, query);
    return opening;
  }

  private void addPriorPurchases(
      Map<String, BigDecimal> opening,
      ReportQuery query,
      Map<String, VendorPurchaseInvoice> invoiceIndex) {
    for (VendorPurchaseInvoice invoice : invoiceIndex.values()) {
      LocalDate day = toShopDate(VendorMoneyMisMapper.effectiveInvoiceInstant(invoice));
      if (day == null || !day.isBefore(query.from()) || !query.matchesVendor(invoice.getVendorId())) {
        continue;
      }
      // Only the unpaid leg carries forward; cash and online were settled at the time.
      addDelta(opening, invoice.getVendorId(), tenderFor(invoice).creditAmount());
    }
  }

  private void addPriorReturns(
      Map<String, BigDecimal> opening,
      ReportQuery query,
      Map<String, VendorPurchaseInvoice> invoiceIndex) {
    List<VendorPurchaseReturn> priorReturns =
        vendorPurchaseLedger.findReturnsByCreatedAt(
            query.shopId(), Instant.EPOCH, query.fromInstant().minusNanos(1));

    for (VendorPurchaseReturn ret : priorReturns) {
      VendorPurchaseInvoice linked =
          ret.getVendorPurchaseInvoiceId() != null
              ? invoiceIndex.get(ret.getVendorPurchaseInvoiceId())
              : null;
      String rowVendorId = linked != null ? linked.getVendorId() : null;
      if (!StringUtils.hasText(rowVendorId) || !query.matchesVendor(rowVendorId)) {
        continue;
      }
      addDelta(opening, rowVendorId, returnCreditLeg(ret).negate());
    }
  }

  /** A return with no refund split recorded is treated as wholly reducing credit. */
  private BigDecimal returnCreditLeg(VendorPurchaseReturn ret) {
    BigDecimal creditLeg = zeroIfNull(ret.getRefundToCredit());
    if (creditLeg.signum() == 0 && hasNoRecordedRefund(ret)) {
      return zeroIfNull(ret.getReturnAmount());
    }
    return creditLeg;
  }

  private boolean hasNoRecordedRefund(VendorPurchaseReturn ret) {
    return zeroIfNull(ret.getRefundCash()).signum() == 0
        && zeroIfNull(ret.getRefundOnline()).signum() == 0
        && zeroIfNull(ret.getRefundToCredit()).signum() == 0;
  }

  private void addPriorCreditEntries(Map<String, BigDecimal> opening, ReportQuery query) {
    List<CreditEntry> priorEntries =
        vendorCreditLedger.findVendorEntriesByTxnDate(
            query.shopId(), null, LocalDate.EPOCH, query.from().minusDays(1));

    for (CreditEntry entry : priorEntries) {
      if (!query.matchesVendor(entry.getPartyRefId()) || isAutoPurchaseCharge(entry)) {
        continue;
      }
      BigDecimal amount = zeroIfNull(entry.getAmount());
      boolean reducesPayable =
          entry.getEntryType() == CreditEntryType.SETTLEMENT
              || entry.getEntryType() == CreditEntryType.RETURN;
      addDelta(opening, entry.getPartyRefId(), reducesPayable ? amount.negate() : amount);
    }
  }

  // ---------------------------------------------------------------------------
  // Filtering, ordering, balances
  // ---------------------------------------------------------------------------

  private List<VendorMoneyMisRowDto> applyFilters(
      List<VendorMoneyMisRowDto> events, ReportQuery query) {
    return events.stream()
        .filter(row -> matchesTxnType(row, query.txnTypes()))
        .filter(row -> matchesMoneyFilter(row, query.moneyFilter()))
        .filter(row -> matchesSearch(row, query.search()))
        .toList();
  }

  private boolean matchesTxnType(VendorMoneyMisRowDto row, Set<MisTxnType> txnTypes) {
    if (txnTypes.isEmpty()) {
      return true;
    }
    return MisTxnType.parse(row.getTxnType()).map(txnTypes::contains).orElse(false);
  }

  private boolean matchesMoneyFilter(VendorMoneyMisRowDto row, MoneyFilter filter) {
    return switch (filter) {
      case ALL -> true;
      case HAS_CASH -> isNonZero(row.getCashAmount());
      case HAS_ONLINE -> isNonZero(row.getOnlineAmount());
      case HAS_CREDIT -> isNonZero(row.getCreditAmount());
      case FULLY_PAID -> !isNonZero(row.getCreditAmount()) && isNonZero(row.getTotalAmount());
      case MIXED -> countNonZeroLegs(row) > 1;
    };
  }

  private int countNonZeroLegs(VendorMoneyMisRowDto row) {
    int legs = 0;
    if (isNonZero(row.getCashAmount())) legs++;
    if (isNonZero(row.getOnlineAmount())) legs++;
    if (isNonZero(row.getCreditAmount())) legs++;
    return legs;
  }

  private boolean matchesSearch(VendorMoneyMisRowDto row, String search) {
    if (!StringUtils.hasText(search)) {
      return true;
    }
    String needle = lowerOrEmpty(search.trim());
    return containsIgnoreCase(row.getVendorName(), needle)
        || containsIgnoreCase(row.getRefNo(), needle)
        || containsIgnoreCase(row.getTxnId(), needle)
        || containsIgnoreCase(row.getAgainstRefNo(), needle);
  }

  private List<VendorMoneyMisRowDto> sortChronologically(List<VendorMoneyMisRowDto> events) {
    List<VendorMoneyMisRowDto> sorted = new ArrayList<>(events);
    sorted.sort(
        Comparator.comparing(
                VendorMoneyMisRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                VendorMoneyMisRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(row -> lowerOrEmpty(row.getVendorName())));
    return sorted;
  }

  /**
   * Inserts each vendor's opening row and stamps a running payable on every row.
   *
   * <p>The opening row is emitted lazily at a vendor's first event rather than up front, so it sits
   * immediately above the rows it explains.
   */
  private List<VendorMoneyMisRowDto> withRunningBalances(
      List<VendorMoneyMisRowDto> events,
      Map<String, BigDecimal> openingByVendor,
      Map<String, String> vendorNames) {
    Map<String, BigDecimal> running = new HashMap<>();
    openingByVendor.forEach((vendorId, amount) -> running.put(vendorId, toMoneyScale(amount)));

    List<VendorMoneyMisRowDto> out = new ArrayList<>();
    Set<String> seenVendors = new HashSet<>();

    for (VendorMoneyMisRowDto row : events) {
      String vendorId = row.getVendorId() != null ? row.getVendorId() : "";

      if (seenVendors.add(vendorId) && hasOpeningBalance(openingByVendor, vendorId)) {
        BigDecimal opening = toMoneyScale(openingByVendor.get(vendorId));
        running.put(vendorId, opening);
        out.add(
            mapper.toOpeningRow(
                vendorId, opening, row.getTxnDate(), row.getVendorName(), vendorNames));
      }

      BigDecimal balance =
          toMoneyScale(running.getOrDefault(vendorId, zeroMoney()).add(payableDelta(row)));
      running.put(vendorId, balance);
      row.setBalanceAfter(balance);
      out.add(row);
    }
    return out;
  }

  private boolean hasOpeningBalance(Map<String, BigDecimal> openingByVendor, String vendorId) {
    BigDecimal opening = openingByVendor.get(vendorId);
    return opening != null && opening.signum() != 0;
  }

  /** How much a row moves the payable: positive owes more, negative owes less. */
  private BigDecimal payableDelta(VendorMoneyMisRowDto row) {
    MisTxnType type = MisTxnType.parse(row.getTxnType()).orElse(null);
    if (type == null) {
      return zeroMoney();
    }
    if (type.increasesPayable()) {
      return zeroIfNull(row.getCreditAmount());
    }
    return switch (type) {
      case VENDOR_PAYMENT -> settlementDelta(row);
      case VENDOR_RETURN -> returnDelta(row);
      default -> zeroMoney();
    };
  }

  /** A settlement reduces the payable by whatever actually moved. */
  private BigDecimal settlementDelta(VendorMoneyMisRowDto row) {
    BigDecimal paid = zeroIfNull(row.getCashAmount()).add(zeroIfNull(row.getOnlineAmount()));
    if (paid.signum() == 0) {
      paid = zeroIfNull(row.getTotalAmount()).abs();
    }
    return paid.negate();
  }

  /**
   * A return reduces the payable by its credit leg.
   *
   * <p>When no credit leg was recorded the total is used as-is — return totals are already negative,
   * so it is a reduction either way.
   */
  private BigDecimal returnDelta(VendorMoneyMisRowDto row) {
    BigDecimal credit = zeroIfNull(row.getCreditAmount()).abs();
    return credit.signum() == 0 ? zeroIfNull(row.getTotalAmount()) : credit.negate();
  }

  private List<VendorMoneyMisRowDto> capRows(List<VendorMoneyMisRowDto> rows) {
    if (rows.size() <= MAX_ROWS) {
      return rows;
    }
    log.info("Vendor money MIS truncated from {} to {} rows", rows.size(), MAX_ROWS);
    return new ArrayList<>(rows.subList(0, MAX_ROWS));
  }

  // ---------------------------------------------------------------------------
  // Summary
  // ---------------------------------------------------------------------------

  private VendorMoneyMisSummaryDto buildSummary(
      List<VendorMoneyMisRowDto> rows,
      Map<String, BigDecimal> openingByVendor,
      Map<String, String> vendorNames) {
    BigDecimal cash = zeroMoney();
    BigDecimal online = zeroMoney();
    BigDecimal credit = zeroMoney();
    BigDecimal purchases = zeroMoney();
    Map<String, VendorMoneyMisRowDto> lastRowByVendor = new LinkedHashMap<>();

    for (VendorMoneyMisRowDto row : rows) {
      if (row.isOpening()) {
        continue;
      }
      cash = cash.add(zeroIfNull(row.getCashAmount()));
      online = online.add(zeroIfNull(row.getOnlineAmount()));
      credit = credit.add(zeroIfNull(row.getCreditAmount()));
      if (MisTxnType.VENDOR_PURCHASE.name().equals(row.getTxnType())) {
        purchases = purchases.add(zeroIfNull(row.getTotalAmount()));
      }
      if (row.getVendorId() != null) {
        lastRowByVendor.put(row.getVendorId(), row);
      }
    }

    return VendorMoneyMisSummaryDto.builder()
        .openingBalanceTotal(sumOpening(openingByVendor))
        .periodCashTotal(toMoneyScale(cash))
        .periodOnlineTotal(toMoneyScale(online))
        .periodCreditTotal(toMoneyScale(credit))
        .periodPurchaseTotal(toMoneyScale(purchases))
        .currentPayableTotal(currentPayable(openingByVendor, lastRowByVendor))
        .vendorSummaries(vendorSummaries(openingByVendor, lastRowByVendor, vendorNames))
        .build();
  }

  private BigDecimal sumOpening(Map<String, BigDecimal> openingByVendor) {
    return openingByVendor.values().stream()
        .map(com.inventory.analytics.utils.VendorMoneyMisUtils::toMoneyScale)
        .reduce(zeroMoney(), BigDecimal::add);
  }

  /**
   * Closing payable across vendors.
   *
   * <p>Vendors with an opening balance but no activity in the window still owe it, so they are
   * added from the opening map rather than dropped for having no rows.
   */
  private BigDecimal currentPayable(
      Map<String, BigDecimal> openingByVendor,
      Map<String, VendorMoneyMisRowDto> lastRowByVendor) {
    BigDecimal total =
        lastRowByVendor.values().stream()
            .map(row -> zeroIfNull(row.getBalanceAfter()))
            .reduce(zeroMoney(), BigDecimal::add);

    for (Map.Entry<String, BigDecimal> entry : openingByVendor.entrySet()) {
      if (!lastRowByVendor.containsKey(entry.getKey())) {
        total = total.add(toMoneyScale(entry.getValue()));
      }
    }
    return toMoneyScale(total);
  }

  private List<VendorMoneyMisVendorSummaryDto> vendorSummaries(
      Map<String, BigDecimal> openingByVendor,
      Map<String, VendorMoneyMisRowDto> lastRowByVendor,
      Map<String, String> vendorNames) {
    Set<String> vendorIds = new HashSet<>(openingByVendor.keySet());
    vendorIds.addAll(lastRowByVendor.keySet());

    List<VendorMoneyMisVendorSummaryDto> summaries = new ArrayList<>(vendorIds.size());
    for (String vendorId : vendorIds) {
      BigDecimal opening = toMoneyScale(openingByVendor.getOrDefault(vendorId, zeroMoney()));
      VendorMoneyMisRowDto last = lastRowByVendor.get(vendorId);
      BigDecimal closing = last != null ? zeroIfNull(last.getBalanceAfter()) : opening;
      summaries.add(mapper.toVendorSummary(vendorId, opening, closing, vendorNames));
    }
    summaries.sort(Comparator.comparing(s -> lowerOrEmpty(s.getVendorName())));
    return summaries;
  }

  // ---------------------------------------------------------------------------
  // Export shaping
  // ---------------------------------------------------------------------------

  /** Projects the report onto the generic table model the document service renders. */
  private TabularReport toTabularReport(VendorMoneyMisResponse report, String shopName) {
    List<List<Object>> rows = new ArrayList<>(report.getRows().size());
    for (VendorMoneyMisRowDto row : report.getRows()) {
      // Arrays.asList rather than List.of: cells are legitimately null and List.of rejects them.
      rows.add(
          Arrays.asList(
              row.getTxnDate(),
              row.getVendorName(),
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
        "Vendor Money MIS",
        (shopName != null ? shopName : "Shop") + " — Vendor Money MIS",
        periodSubtitle(report),
        REPORT_COLUMNS,
        rows,
        totalsRow(report.getSummary()),
        summaryNotes(report.getSummary()));
  }

  private String periodSubtitle(VendorMoneyMisResponse report) {
    return "Period: "
        + formatDate(report.getFrom())
        + " – "
        + formatDate(report.getTo())
        + " · Timezone Asia/Kolkata";
  }

  private String formatDate(LocalDate date) {
    return date != null ? date.format(DATE_FORMAT) : "";
  }

  private List<Object> totalsRow(VendorMoneyMisSummaryDto summary) {
    if (summary == null) {
      return null;
    }
    List<Object> totals = new ArrayList<>(REPORT_COLUMNS.size());
    totals.add("Totals");
    // Blank out Supplier / Txn ID / Transaction / Invoice before money columns.
    for (int i = 1; i < 5; i++) {
      totals.add("");
    }
    totals.add(summary.getPeriodPurchaseTotal());
    totals.add(summary.getPeriodCashTotal());
    totals.add(summary.getPeriodOnlineTotal());
    totals.add(summary.getPeriodCreditTotal());
    totals.add(summary.getCurrentPayableTotal());
    return totals;
  }

  private List<String> summaryNotes(VendorMoneyMisSummaryDto summary) {
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
            + " · Current payable: "
            + summary.getCurrentPayableTotal());
  }

  private static String exportFilename(VendorMoneyMisResponse report, String extension) {
    return "vendor-money-mis-"
        + (report.getFrom() != null ? report.getFrom() : "from")
        + "-"
        + (report.getTo() != null ? report.getTo() : "to")
        + "."
        + extension;
  }

  /** How an invoice was tendered — derived by the product module, which owns the invoice shape. */
  private VendorPurchasePaymentBreakdown.Result tenderFor(VendorPurchaseInvoice invoice) {
    return vendorPurchaseLedger.tenderFor(invoice);
  }
}
