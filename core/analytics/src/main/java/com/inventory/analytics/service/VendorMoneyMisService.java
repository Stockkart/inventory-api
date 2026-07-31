package com.inventory.analytics.service;

import com.inventory.analytics.rest.dto.response.VendorMoneyMisVendorSummaryDto;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisResponse;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisRowDto;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisSummaryDto;
import com.inventory.analytics.util.TxnTypeParser;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.product.service.VendorPurchasePaymentBreakdown;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Vendor Money MIS: Excel-style row ledger of purchase / payment / return / charge events with
 * cash/online/credit columns and running payable balance per vendor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorMoneyMisService {

  public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
  private static final int MAX_ROWS = 2000;
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
  private static final NumberFormat MONEY =
      NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
  private static final List<String> EXCEL_HEADERS =
      List.of(
          "Date",
          "Supplier",
          "Txn ID",
          "Transaction",
          "Invoice",
          "Against",
          "Bill Amount",
          "Cash",
          "Online",
          "Credit",
          "Outstanding");

  private final VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;
  private final VendorPurchaseReturnRepository vendorPurchaseReturnRepository;
  private final CreditEntryRepository creditEntryRepository;
  private final VendorRepository vendorRepository;
  private final DocumentService documentService;

  /** Binary download payload (bytes + suggested attachment filename). */
  public record ExportFile(byte[] content, String filename) {}

  public VendorMoneyMisResponse getVendorMis(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      String txnTypesCsv,
      String moneyFilter,
      String q) {
    return getVendorMis(
        shopId, from, to, vendorId, TxnTypeParser.parse(txnTypesCsv), moneyFilter, q);
  }

  public ExportFile exportExcel(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      String txnTypesCsv,
      String moneyFilter,
      String q) {
    VendorMoneyMisResponse report =
        getVendorMis(shopId, from, to, vendorId, txnTypesCsv, moneyFilter, q);
    return new ExportFile(buildExcel(report), exportFilename(report, "xlsx"));
  }

  public ExportFile exportPdf(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      String txnTypesCsv,
      String moneyFilter,
      String q) {
    VendorMoneyMisResponse report =
        getVendorMis(shopId, from, to, vendorId, txnTypesCsv, moneyFilter, q);
    return new ExportFile(
        documentService.generatePdfFromHtml(buildPdfHtml(report, "Shop")),
        exportFilename(report, "pdf"));
  }

  public VendorMoneyMisResponse getVendorMis(
      String shopId,
      LocalDate from,
      LocalDate to,
      String vendorId,
      Set<String> txnTypes,
      String moneyFilter,
      String q) {
    LocalDate rangeTo = to != null ? to : LocalDate.now(SHOP_ZONE);
    LocalDate rangeFrom = from != null ? from : rangeTo.withDayOfMonth(1);

    Map<String, String> vendorNames = loadVendorNames(shopId);
    List<VendorMoneyMisRowDto> events = new ArrayList<>();

    Instant fromInstant = rangeFrom.atStartOfDay(SHOP_ZONE).toInstant();
    Instant toInstantExclusive = rangeTo.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();

    // Purchases — prefer invoiceDate range; also pull createdAt window to catch missing invoiceDate
    List<VendorPurchaseInvoice> invoices = new ArrayList<>();
    invoices.addAll(
        vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceDateBetween(
            shopId, fromInstant, toInstantExclusive.minusNanos(1)));
    invoices.addAll(
        vendorPurchaseInvoiceRepository.findByShopIdAndCreatedAtBetween(
            shopId, fromInstant, toInstantExclusive.minusNanos(1)));
    Map<String, VendorPurchaseInvoice> invoiceById = new LinkedHashMap<>();
    for (VendorPurchaseInvoice inv : invoices) {
      if (inv.getId() != null) {
        invoiceById.putIfAbsent(inv.getId(), inv);
      }
    }

    // Need all invoices for opening-balance + against-ref (same shop); load lightly by createdAt before from
    List<VendorPurchaseInvoice> priorInvoices =
        vendorPurchaseInvoiceRepository.findByShopIdAndCreatedAtBetween(
            shopId, Instant.EPOCH, fromInstant.minusNanos(1));
    for (VendorPurchaseInvoice inv : priorInvoices) {
      if (inv.getId() != null) {
        invoiceById.putIfAbsent(inv.getId(), inv);
      }
    }

    for (VendorPurchaseInvoice inv : invoiceById.values()) {
      LocalDate day = toShopDate(inv.getInvoiceDate() != null ? inv.getInvoiceDate() : inv.getCreatedAt());
      if (day == null || day.isBefore(rangeFrom) || day.isAfter(rangeTo)) {
        continue;
      }
      if (StringUtils.hasText(vendorId) && !vendorId.equals(inv.getVendorId())) {
        continue;
      }
      events.add(toPurchaseRow(inv, vendorNames));
    }

    List<VendorPurchaseReturn> returns =
        vendorPurchaseReturnRepository.findByShopIdAndCreatedAtBetween(
            shopId, fromInstant, toInstantExclusive.minusNanos(1));
    for (VendorPurchaseReturn ret : returns) {
      LocalDate day = toShopDate(ret.getCreatedAt());
      if (day == null || day.isBefore(rangeFrom) || day.isAfter(rangeTo)) {
        continue;
      }
      VendorPurchaseInvoice linked =
          ret.getVendorPurchaseInvoiceId() != null
              ? invoiceById.get(ret.getVendorPurchaseInvoiceId())
              : null;
      if (linked == null && StringUtils.hasText(ret.getVendorPurchaseInvoiceId())) {
        linked =
            vendorPurchaseInvoiceRepository
                .findById(ret.getVendorPurchaseInvoiceId())
                .orElse(null);
        if (linked != null) {
          invoiceById.put(linked.getId(), linked);
        }
      }
      String rowVendorId = linked != null ? linked.getVendorId() : null;
      if (StringUtils.hasText(vendorId) && !vendorId.equals(rowVendorId)) {
        continue;
      }
      events.add(toReturnRow(ret, linked, vendorNames));
    }

    List<CreditEntryType> creditTypes =
        Arrays.asList(CreditEntryType.SETTLEMENT, CreditEntryType.CHARGE, CreditEntryType.ADJUSTMENT);
    Map<String, CreditEntry> creditById = new LinkedHashMap<>();
    for (CreditEntry entry :
        creditEntryRepository.findByShopIdAndPartyTypeAndEntryTypeInAndTxnDateBetween(
            shopId, CreditPartyType.VENDOR, creditTypes, rangeFrom, rangeTo)) {
      if (entry.getId() != null) {
        creditById.put(entry.getId(), entry);
      }
    }
    for (CreditEntry entry :
        creditEntryRepository.findByShopIdAndPartyTypeAndCreatedAtBetween(
            shopId, CreditPartyType.VENDOR, fromInstant, toInstantExclusive.minusNanos(1))) {
      if (entry.getId() == null) {
        continue;
      }
      if (entry.getEntryType() != CreditEntryType.SETTLEMENT
          && entry.getEntryType() != CreditEntryType.CHARGE
          && entry.getEntryType() != CreditEntryType.ADJUSTMENT) {
        continue;
      }
      LocalDate day =
          entry.getTxnDate() != null ? entry.getTxnDate() : toShopDate(entry.getCreatedAt());
      if (day == null || day.isBefore(rangeFrom) || day.isAfter(rangeTo)) {
        continue;
      }
      creditById.putIfAbsent(entry.getId(), entry);
    }
    for (CreditEntry entry : creditById.values()) {
      if (StringUtils.hasText(vendorId) && !vendorId.equals(entry.getPartyRefId())) {
        continue;
      }
      // Skip auto charges that duplicate vendor purchase invoices
      if (entry.getEntryType() == CreditEntryType.CHARGE
          && entry.getSourceKey() != null
          && entry.getSourceKey().startsWith("PURCHASE:CREDIT:")) {
        continue;
      }
      events.add(toCreditRow(entry, vendorNames));
    }

    // Opening balances per party (events before from)
    Map<String, BigDecimal> openingByParty =
        computeOpeningBalances(shopId, rangeFrom, vendorId, vendorNames, invoiceById);

    // Filter types / money / search on event rows (before opening rows)
    events = filterEvents(events, txnTypes, moneyFilter, q);

    // Sort events
    events.sort(
        Comparator.comparing(VendorMoneyMisRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(VendorMoneyMisRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                r -> r.getVendorName() != null ? r.getVendorName().toLowerCase(Locale.ROOT) : "",
                Comparator.nullsLast(Comparator.naturalOrder())));

    // Insert opening rows and compute running balances
    List<VendorMoneyMisRowDto> withBalance = applyRunningBalances(events, openingByParty, vendorNames);

    if (withBalance.size() > MAX_ROWS) {
      withBalance = new ArrayList<>(withBalance.subList(0, MAX_ROWS));
    }

    VendorMoneyMisSummaryDto summary = buildSummary(withBalance, openingByParty, vendorNames);

    return VendorMoneyMisResponse.builder()
        .from(rangeFrom)
        .to(rangeTo)
        .rows(withBalance)
        .summary(summary)
        .build();
  }

  private Map<String, BigDecimal> computeOpeningBalances(
      String shopId,
      LocalDate from,
      String vendorId,
      Map<String, String> vendorNames,
      Map<String, VendorPurchaseInvoice> invoiceById) {
    Map<String, BigDecimal> opening = new HashMap<>();
    Instant fromInstant = from.atStartOfDay(SHOP_ZONE).toInstant();

    for (VendorPurchaseInvoice inv : invoiceById.values()) {
      LocalDate day = toShopDate(inv.getInvoiceDate() != null ? inv.getInvoiceDate() : inv.getCreatedAt());
      if (day == null || !day.isBefore(from)) {
        continue;
      }
      if (StringUtils.hasText(vendorId) && !vendorId.equals(inv.getVendorId())) {
        continue;
      }
      VendorPurchasePaymentBreakdown.Result tender = tenderForInvoice(inv);
      addDelta(opening, inv.getVendorId(), tender.creditAmount());
    }

    List<VendorPurchaseReturn> priorReturns =
        vendorPurchaseReturnRepository.findByShopIdAndCreatedAtBetween(
            shopId, Instant.EPOCH, fromInstant.minusNanos(1));
    for (VendorPurchaseReturn ret : priorReturns) {
      VendorPurchaseInvoice linked =
          ret.getVendorPurchaseInvoiceId() != null
              ? invoiceById.get(ret.getVendorPurchaseInvoiceId())
              : null;
      String rowVendorId = linked != null ? linked.getVendorId() : null;
      if (!StringUtils.hasText(rowVendorId)) {
        continue;
      }
      if (StringUtils.hasText(vendorId) && !vendorId.equals(rowVendorId)) {
        continue;
      }
      BigDecimal creditLeg = nz(ret.getRefundToCredit());
      if (creditLeg.signum() == 0 && isAllZeroRefund(ret)) {
        creditLeg = nz(ret.getReturnAmount());
      }
      // Returns reduce payable
      addDelta(opening, rowVendorId, creditLeg.negate());
    }

    List<CreditEntry> priorCredits =
        creditEntryRepository.findByShopIdAndPartyTypeAndTxnDateBetween(
            shopId, CreditPartyType.VENDOR, LocalDate.of(1970, 1, 1), from.minusDays(1));
    for (CreditEntry entry : priorCredits) {
      if (StringUtils.hasText(vendorId) && !vendorId.equals(entry.getPartyRefId())) {
        continue;
      }
      if (entry.getEntryType() == CreditEntryType.CHARGE
          && entry.getSourceKey() != null
          && entry.getSourceKey().startsWith("PURCHASE:CREDIT:")) {
        continue;
      }
      BigDecimal amt = nz(entry.getAmount());
      if (entry.getEntryType() == CreditEntryType.SETTLEMENT
          || entry.getEntryType() == CreditEntryType.RETURN) {
        addDelta(opening, entry.getPartyRefId(), amt.negate());
      } else {
        addDelta(opening, entry.getPartyRefId(), amt);
      }
    }
    return opening;
  }

  private List<VendorMoneyMisRowDto> applyRunningBalances(
      List<VendorMoneyMisRowDto> events,
      Map<String, BigDecimal> openingByParty,
      Map<String, String> vendorNames) {
    Map<String, BigDecimal> running = new HashMap<>();
    for (Map.Entry<String, BigDecimal> e : openingByParty.entrySet()) {
      running.put(e.getKey(), scale(e.getValue()));
    }

    List<VendorMoneyMisRowDto> out = new ArrayList<>();
    Set<String> opened = new HashSet<>();

    for (VendorMoneyMisRowDto row : events) {
      String pid = row.getVendorId() != null ? row.getVendorId() : "";
      if (!opened.contains(pid) && openingByParty.containsKey(pid) && openingByParty.get(pid).signum() != 0) {
        BigDecimal open = scale(openingByParty.get(pid));
        running.put(pid, open);
        out.add(
            VendorMoneyMisRowDto.builder()
                .txnId("OPEN-" + shortId(pid))
                .txnType("OPENING")
                .txnTypeLabel("Opening")
                .vendorId(pid)
                .vendorName(vendorNames.getOrDefault(pid, row.getVendorName()))
                .txnDate(row.getTxnDate())
                .postedAt(null)
                .refNo("Opening balance")
                .totalAmount(open)
                .cashAmount(zero())
                .onlineAmount(zero())
                .creditAmount(open)
                .balanceAfter(open)
                .sourceType("OPENING")
                .sourceId(null)
                .opening(true)
                .build());
        opened.add(pid);
      }

      BigDecimal bal = scale(running.getOrDefault(pid, zero()));
      BigDecimal delta = partyDelta(row);
      bal = scale(bal.add(delta));
      running.put(pid, bal);
      row.setBalanceAfter(bal);
      out.add(row);
      opened.add(pid);
    }
    return out;
  }

  private BigDecimal partyDelta(VendorMoneyMisRowDto row) {
    String type = row.getTxnType();
    if ("VENDOR_PURCHASE".equals(type) || "VENDOR_CREDIT_CHARGE".equals(type) || "OPENING".equals(type)) {
      return nz(row.getCreditAmount());
    }
    if ("VENDOR_PAYMENT".equals(type)) {
      // Settlement reduces payable; amount is in cash/online (or total)
      BigDecimal paid = nz(row.getCashAmount()).add(nz(row.getOnlineAmount()));
      if (paid.signum() == 0) {
        paid = nz(row.getTotalAmount()).abs();
      }
      return paid.negate();
    }
    if ("VENDOR_RETURN".equals(type)) {
      // Reduce payable primarily by credit leg; cash/online refunds also reduce what we owed if
      // previously paid, but design: credit leg reduces payable; cash/online are money out from
      // vendor back to us. Net payable change ≈ -credit (and if refund was credit note reducing
      // invoice). Using -|credit| or -|total| when credit is zero.
      BigDecimal credit = nz(row.getCreditAmount()).abs();
      if (credit.signum() == 0) {
        return nz(row.getTotalAmount()); // already negative for returns
      }
      return credit.negate();
    }
    return zero();
  }

  private VendorMoneyMisSummaryDto buildSummary(
      List<VendorMoneyMisRowDto> rows,
      Map<String, BigDecimal> openingByParty,
      Map<String, String> vendorNames) {
    BigDecimal cash = zero();
    BigDecimal online = zero();
    BigDecimal credit = zero();
    BigDecimal purchase = zero();
    Map<String, VendorMoneyMisRowDto> lastByParty = new LinkedHashMap<>();

    for (VendorMoneyMisRowDto row : rows) {
      if (row.isOpening()) {
        continue;
      }
      cash = cash.add(nz(row.getCashAmount()));
      online = online.add(nz(row.getOnlineAmount()));
      credit = credit.add(nz(row.getCreditAmount()));
      if ("VENDOR_PURCHASE".equals(row.getTxnType())) {
        purchase = purchase.add(nz(row.getTotalAmount()));
      }
      if (row.getVendorId() != null) {
        lastByParty.put(row.getVendorId(), row);
      }
    }

    BigDecimal openingTotal =
        openingByParty.values().stream().map(this::scale).reduce(zero(), BigDecimal::add);
    BigDecimal currentPayable =
        lastByParty.values().stream()
            .map(r -> nz(r.getBalanceAfter()))
            .reduce(zero(), BigDecimal::add);

    // Include parties that only have opening
    for (Map.Entry<String, BigDecimal> e : openingByParty.entrySet()) {
      if (!lastByParty.containsKey(e.getKey())) {
        currentPayable = currentPayable.add(scale(e.getValue()));
      }
    }

    List<VendorMoneyMisVendorSummaryDto> vendorSummaries = new ArrayList<>();
    Set<String> allParties = new HashSet<>(openingByParty.keySet());
    allParties.addAll(lastByParty.keySet());
    for (String pid : allParties) {
      BigDecimal open = scale(openingByParty.getOrDefault(pid, zero()));
      VendorMoneyMisRowDto last = lastByParty.get(pid);
      BigDecimal closing = last != null ? nz(last.getBalanceAfter()) : open;
      vendorSummaries.add(
          VendorMoneyMisVendorSummaryDto.builder()
              .vendorId(pid)
              .vendorName(vendorNames.getOrDefault(pid, pid))
              .openingBalance(open)
              .closingBalanceInPeriod(closing)
              .currentBalance(closing)
              .build());
    }
    vendorSummaries.sort(
        Comparator.comparing(
            p -> p.getVendorName() != null ? p.getVendorName().toLowerCase(Locale.ROOT) : "",
            Comparator.nullsLast(Comparator.naturalOrder())));

    return VendorMoneyMisSummaryDto.builder()
        .openingBalanceTotal(openingTotal)
        .periodCashTotal(scale(cash))
        .periodOnlineTotal(scale(online))
        .periodCreditTotal(scale(credit))
        .periodPurchaseTotal(scale(purchase))
        .currentPayableTotal(scale(currentPayable))
        .vendorSummaries(vendorSummaries)
        .build();
  }

  private List<VendorMoneyMisRowDto> filterEvents(
      List<VendorMoneyMisRowDto> events, Set<String> txnTypes, String moneyFilter, String q) {
    return events.stream()
        .filter(
            r -> {
              if (txnTypes != null && !txnTypes.isEmpty() && !txnTypes.contains(r.getTxnType())) {
                return false;
              }
              if (!matchesMoneyFilter(r, moneyFilter)) {
                return false;
              }
              if (StringUtils.hasText(q)) {
                String needle = q.trim().toLowerCase(Locale.ROOT);
                return contains(r.getVendorName(), needle)
                    || contains(r.getRefNo(), needle)
                    || contains(r.getTxnId(), needle)
                    || contains(r.getAgainstRefNo(), needle);
              }
              return true;
            })
        .collect(Collectors.toList());
  }

  private boolean matchesMoneyFilter(VendorMoneyMisRowDto r, String moneyFilter) {
    if (!StringUtils.hasText(moneyFilter) || "ALL".equalsIgnoreCase(moneyFilter)) {
      return true;
    }
    return switch (moneyFilter.trim().toUpperCase(Locale.ROOT)) {
      case "HAS_CASH" -> nz(r.getCashAmount()).signum() != 0;
      case "HAS_ONLINE" -> nz(r.getOnlineAmount()).signum() != 0;
      case "HAS_CREDIT" -> nz(r.getCreditAmount()).signum() != 0;
      case "FULLY_PAID" -> nz(r.getCreditAmount()).signum() == 0 && nz(r.getTotalAmount()).signum() != 0;
      case "MIXED" -> {
        int legs = 0;
        if (nz(r.getCashAmount()).signum() != 0) legs++;
        if (nz(r.getOnlineAmount()).signum() != 0) legs++;
        if (nz(r.getCreditAmount()).signum() != 0) legs++;
        yield legs > 1;
      }
      default -> true;
    };
  }

  private VendorMoneyMisRowDto toPurchaseRow(
      VendorPurchaseInvoice inv, Map<String, String> vendorNames) {
    VendorPurchasePaymentBreakdown.Result tender = tenderForInvoice(inv);
    Instant posted = inv.getCreatedAt() != null ? inv.getCreatedAt() : inv.getInvoiceDate();
    return VendorMoneyMisRowDto.builder()
        .txnId("VPUR-" + shortId(inv.getId()))
        .txnType("VENDOR_PURCHASE")
        .txnTypeLabel("Purchase")
        .vendorId(inv.getVendorId())
        .vendorName(vendorNames.getOrDefault(inv.getVendorId(), inv.getVendorId()))
        .txnDate(toShopDate(inv.getInvoiceDate() != null ? inv.getInvoiceDate() : inv.getCreatedAt()))
        .postedAt(posted)
        .refNo(inv.getInvoiceNo())
        .totalAmount(scale(nz(inv.getInvoiceTotal()).signum() > 0 ? inv.getInvoiceTotal() : tender.paidAmount().add(tender.creditAmount())))
        .cashAmount(tender.cashAmount())
        .onlineAmount(tender.onlineAmount())
        .creditAmount(tender.creditAmount())
        .sourceType("VENDOR_PURCHASE_INVOICE")
        .sourceId(inv.getId())
        .opening(false)
        .build();
  }

  private VendorMoneyMisRowDto toReturnRow(
      VendorPurchaseReturn ret,
      VendorPurchaseInvoice linked,
      Map<String, String> vendorNames) {
    String vendorId = linked != null ? linked.getVendorId() : null;
    BigDecimal total = nz(ret.getReturnAmount()).negate();
    BigDecimal cash = nz(ret.getRefundCash()).negate();
    BigDecimal online = nz(ret.getRefundOnline()).negate();
    BigDecimal credit = nz(ret.getRefundToCredit()).negate();
    if (cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0) {
      credit = total;
    }
    return VendorMoneyMisRowDto.builder()
        .txnId("VRET-" + shortId(ret.getId()))
        .txnType("VENDOR_RETURN")
        .txnTypeLabel("Return")
        .vendorId(vendorId)
        .vendorName(vendorNames.getOrDefault(vendorId, vendorId))
        .txnDate(toShopDate(ret.getCreatedAt()))
        .postedAt(ret.getCreatedAt())
        .refNo(ret.getSupplierCreditNoteNo())
        .againstTxnId(linked != null ? "VPUR-" + shortId(linked.getId()) : null)
        .againstRefNo(linked != null ? linked.getInvoiceNo() : null)
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceType("VENDOR_PURCHASE_RETURN")
        .sourceId(ret.getId())
        .opening(false)
        .build();
  }

  private VendorMoneyMisRowDto toCreditRow(CreditEntry entry, Map<String, String> vendorNames) {
    boolean settlement = entry.getEntryType() == CreditEntryType.SETTLEMENT;
    boolean charge =
        entry.getEntryType() == CreditEntryType.CHARGE
            || entry.getEntryType() == CreditEntryType.ADJUSTMENT;
    String txnType = settlement ? "VENDOR_PAYMENT" : "VENDOR_CREDIT_CHARGE";
    String label = settlement ? "Payment" : "Credit charge";
    String prefix = settlement ? "VPAY-" : "VCHG-";
    BigDecimal amount = nz(entry.getAmount());
    BigDecimal cash = zero();
    BigDecimal online = zero();
    BigDecimal credit = zero();
    if (settlement) {
      String method =
          entry.getPaymentMethod() != null ? entry.getPaymentMethod().trim().toUpperCase() : "CASH";
      if (Set.of("ONLINE", "UPI", "BANK", "CARD").contains(method)) {
        online = amount;
      } else {
        cash = amount;
      }
    } else {
      credit = amount;
    }
    LocalDate day = entry.getTxnDate() != null ? entry.getTxnDate() : toShopDate(entry.getCreatedAt());
    return VendorMoneyMisRowDto.builder()
        .txnId(prefix + shortId(entry.getId()))
        .txnType(txnType)
        .txnTypeLabel(label)
        .vendorId(entry.getPartyRefId())
        .vendorName(vendorNames.getOrDefault(entry.getPartyRefId(), entry.getPartyRefId()))
        .txnDate(day)
        .postedAt(entry.getCreatedAt())
        .refNo(
            StringUtils.hasText(entry.getNote())
                ? entry.getNote()
                : (StringUtils.hasText(entry.getBankRef()) ? entry.getBankRef() : entry.getId()))
        .totalAmount(amount)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceType(settlement ? "VENDOR_PAYMENT" : "VENDOR_CREDIT_CHARGE")
        .sourceId(entry.getId())
        .opening(false)
        .build();
  }

  private VendorPurchasePaymentBreakdown.Result tenderForInvoice(VendorPurchaseInvoice inv) {
    BigDecimal total = nz(inv.getInvoiceTotal());
    if (total.signum() <= 0) {
      total =
          nz(inv.getLineSubTotal())
              .add(nz(inv.getTaxTotal()))
              .add(nz(inv.getShippingCharge()))
              .add(nz(inv.getOtherCharges()))
              .add(nz(inv.getRoundOff()))
              .subtract(nz(inv.getOverallDiscount()));
    }
    if (inv.getCashAmount() != null
        || inv.getOnlineAmount() != null
        || inv.getCreditAmount() != null) {
      return VendorPurchasePaymentBreakdown.resolve(
          total,
          inv.getPaymentMethod(),
          inv.getPaidAmount(),
          inv.getCashAmount(),
          inv.getOnlineAmount(),
          inv.getCreditAmount());
    }
    return VendorPurchasePaymentBreakdown.deriveForReport(
        total, inv.getPaymentMethod(), inv.getPaidAmount());
  }

  private Map<String, String> loadVendorNames(String shopId) {
    Map<String, String> map = new HashMap<>();
    // Resolve names lazily from vendor docs as we encounter party ids in events.
    // Prefill from all invoices for this shop (bounded report windows already load them).
    try {
      for (VendorPurchaseInvoice inv : vendorPurchaseInvoiceRepository.findByShopId(shopId)) {
        if (StringUtils.hasText(inv.getVendorId()) && !map.containsKey(inv.getVendorId())) {
          vendorRepository
              .findById(inv.getVendorId())
              .ifPresent(v -> map.put(v.getId(), v.getName() != null ? v.getName() : v.getId()));
          map.putIfAbsent(inv.getVendorId(), inv.getVendorId());
        }
      }
    } catch (Exception e) {
      log.warn("Could not load vendors for shop {}: {}", shopId, e.getMessage());
    }
    return map;
  }

  private static LocalDate toShopDate(Instant instant) {
    if (instant == null) {
      return null;
    }
    return instant.atZone(SHOP_ZONE).toLocalDate();
  }

  private static String shortId(String id) {
    if (!StringUtils.hasText(id)) {
      return "----";
    }
    String t = id.trim();
    return t.length() <= 4 ? t : t.substring(0, 4);
  }

  private static boolean contains(String hay, String needle) {
    return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static boolean isAllZeroRefund(VendorPurchaseReturn ret) {
    return nz(ret.getRefundCash()).signum() == 0
        && nz(ret.getRefundOnline()).signum() == 0
        && nz(ret.getRefundToCredit()).signum() == 0;
  }

  private void addDelta(Map<String, BigDecimal> map, String vendorId, BigDecimal delta) {
    if (!StringUtils.hasText(vendorId) || delta == null) {
      return;
    }
    map.merge(vendorId, scale(delta), BigDecimal::add);
  }

  private BigDecimal scale(BigDecimal v) {
    return nz(v).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  }

  private byte[] buildExcel(VendorMoneyMisResponse report) {
    String subtitle =
        "Period: "
            + (report.getFrom() != null ? report.getFrom().format(DATE_FMT) : "")
            + " – "
            + (report.getTo() != null ? report.getTo().format(DATE_FMT) : "");

    List<List<Object>> rows = new ArrayList<>();
    for (VendorMoneyMisRowDto row : report.getRows()) {
      List<Object> cells = new ArrayList<>(11);
      cells.add(row.getTxnDate() != null ? row.getTxnDate().format(DATE_FMT) : "");
      cells.add(nullToEmpty(row.getVendorName()));
      cells.add(nullToEmpty(row.getTxnId()));
      cells.add(nullToEmpty(row.getTxnTypeLabel()));
      cells.add(nullToEmpty(row.getRefNo()));
      cells.add(nullToEmpty(row.getAgainstRefNo()));
      cells.add(row.getTotalAmount());
      cells.add(row.getCashAmount());
      cells.add(row.getOnlineAmount());
      cells.add(row.getCreditAmount());
      cells.add(row.getBalanceAfter());
      rows.add(cells);
    }

    List<Object> totalsRow = null;
    VendorMoneyMisSummaryDto summary = report.getSummary();
    if (summary != null) {
      totalsRow =
          Arrays.asList(
              "Totals",
              "",
              "",
              "",
              "",
              "",
              summary.getPeriodPurchaseTotal(),
              summary.getPeriodCashTotal(),
              summary.getPeriodOnlineTotal(),
              summary.getPeriodCreditTotal(),
              summary.getCurrentPayableTotal());
    }

    return documentService.generateExcel(
        "Vendor Money MIS",
        "Vendor Money MIS",
        subtitle,
        EXCEL_HEADERS,
        rows,
        totalsRow);
  }

  private String buildPdfHtml(VendorMoneyMisResponse report, String shopName) {
    StringBuilder sb = new StringBuilder();
    sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/>")
        .append("<style>")
        .append("@page { size: A4 landscape; margin: 12mm; }")
        .append("body{font-family:Arial,sans-serif;font-size:10px;color:#111;}")
        .append("h1{font-size:16px;margin:0 0 4px 0;}")
        .append("p.meta{margin:0 0 10px 0;color:#444;}")
        .append("table{width:100%;border-collapse:collapse;}")
        .append("th,td{border:1px solid #333;padding:4px 6px;text-align:left;}")
        .append("th{background:#eee;}")
        .append("td.num{text-align:right;}")
        .append(".summary{margin-top:12px;}")
        .append("</style></head><body>");
    sb.append("<h1>")
        .append(escape(shopName != null ? shopName : "Shop"))
        .append(" — Vendor Money MIS</h1>");
    sb.append("<p class='meta'>Period: ")
        .append(report.getFrom() != null ? report.getFrom().format(DATE_FMT) : "")
        .append(" – ")
        .append(report.getTo() != null ? report.getTo().format(DATE_FMT) : "")
        .append(" · Timezone Asia/Kolkata</p>");

    sb.append("<table><thead><tr>")
        .append("<th>Date</th><th>Supplier</th><th>Txn ID</th><th>Transaction</th><th>Invoice</th>")
        .append("<th>Against</th><th>Bill Amount</th><th>Cash</th><th>Online</th><th>Credit</th>")
        .append("<th>Outstanding</th></tr></thead><tbody>");

    for (VendorMoneyMisRowDto row : report.getRows()) {
      sb.append("<tr>")
          .append("<td>")
          .append(row.getTxnDate() != null ? row.getTxnDate().format(DATE_FMT) : "")
          .append("</td>")
          .append("<td>")
          .append(escape(row.getVendorName()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getTxnId()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getTxnTypeLabel()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getRefNo()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getAgainstRefNo()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmtMoney(row.getTotalAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmtMoney(row.getCashAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmtMoney(row.getOnlineAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmtMoney(row.getCreditAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmtMoney(row.getBalanceAfter()))
          .append("</td>")
          .append("</tr>");
    }
    sb.append("</tbody></table>");

    VendorMoneyMisSummaryDto s = report.getSummary();
    if (s != null) {
      sb.append("<p class='summary'><strong>Summary</strong> — Cash: ")
          .append(fmtMoney(s.getPeriodCashTotal()))
          .append(" · Online: ")
          .append(fmtMoney(s.getPeriodOnlineTotal()))
          .append(" · Credit: ")
          .append(fmtMoney(s.getPeriodCreditTotal()))
          .append(" · Current payable: ")
          .append(fmtMoney(s.getCurrentPayableTotal()))
          .append("</p>");
    }
    sb.append("</body></html>");
    return sb.toString();
  }

  private static String exportFilename(VendorMoneyMisResponse report, String extension) {
    return "vendor-money-mis-"
        + (report.getFrom() != null ? report.getFrom() : "from")
        + "-"
        + (report.getTo() != null ? report.getTo() : "to")
        + "."
        + extension;
  }

  private static String fmtMoney(BigDecimal v) {
    if (v == null || v.signum() == 0) {
      return "—";
    }
    return MONEY.format(v);
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }
}
