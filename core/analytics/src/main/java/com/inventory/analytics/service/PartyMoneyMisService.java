package com.inventory.analytics.service;

import com.inventory.analytics.rest.dto.response.PartyMoneyMisPartySummaryDto;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisResponse;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisRowDto;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisSummaryDto;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.product.service.VendorPurchasePaymentBreakdown;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
public class PartyMoneyMisService {

  public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
  private static final int MAX_ROWS = 2000;
  /** Synthetic party bucket for walk-in / anonymous cash sales (Purchase.customerId == null). */
  private static final String WALKIN_PARTY_ID = "WALKIN";
  private static final String WALKIN_PARTY_NAME = "Walk-in / Cash sale";

  private final VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;
  private final VendorPurchaseReturnRepository vendorPurchaseReturnRepository;
  private final CreditEntryRepository creditEntryRepository;
  private final VendorRepository vendorRepository;
  private final PurchaseRepository purchaseRepository;
  private final RefundRepository refundRepository;
  private final CustomerRepository customerRepository;

  public PartyMoneyMisResponse getVendorMis(
      String shopId,
      LocalDate from,
      LocalDate to,
      String partyId,
      Set<String> txnTypes,
      String moneyFilter,
      String q) {
    LocalDate rangeTo = to != null ? to : LocalDate.now(SHOP_ZONE);
    LocalDate rangeFrom = from != null ? from : rangeTo.withDayOfMonth(1);

    Map<String, String> vendorNames = loadVendorNames(shopId);
    List<PartyMoneyMisRowDto> events = new ArrayList<>();

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
      if (StringUtils.hasText(partyId) && !partyId.equals(inv.getVendorId())) {
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
      String vendorId = linked != null ? linked.getVendorId() : null;
      if (StringUtils.hasText(partyId) && !partyId.equals(vendorId)) {
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
      if (StringUtils.hasText(partyId) && !partyId.equals(entry.getPartyRefId())) {
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
        computeOpeningBalances(shopId, rangeFrom, partyId, vendorNames, invoiceById);

    // Filter types / money / search on event rows (before opening rows)
    events = filterEvents(events, txnTypes, moneyFilter, q);

    // Sort events
    events.sort(
        Comparator.comparing(PartyMoneyMisRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PartyMoneyMisRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                r -> r.getPartyName() != null ? r.getPartyName().toLowerCase(Locale.ROOT) : "",
                Comparator.nullsLast(Comparator.naturalOrder())));

    // Insert opening rows and compute running balances
    List<PartyMoneyMisRowDto> withBalance = applyRunningBalances(events, openingByParty, vendorNames);

    if (withBalance.size() > MAX_ROWS) {
      withBalance = new ArrayList<>(withBalance.subList(0, MAX_ROWS));
    }

    PartyMoneyMisSummaryDto summary = buildSummary(withBalance, openingByParty, vendorNames);

    return PartyMoneyMisResponse.builder()
        .side("VENDOR")
        .from(rangeFrom)
        .to(rangeTo)
        .rows(withBalance)
        .summary(summary)
        .build();
  }

  /**
   * Customer / Sales Money MIS: Excel-style row ledger of sale / receipt / sales-return / charge
   * events with cash/online/credit columns and a running receivable balance per customer. Walk-in
   * (customerId-less) cash sales roll up under a single synthetic "Walk-in" party.
   */
  public PartyMoneyMisResponse getCustomerMis(
      String shopId,
      LocalDate from,
      LocalDate to,
      String partyId,
      Set<String> txnTypes,
      String moneyFilter,
      String q) {
    LocalDate rangeTo = to != null ? to : LocalDate.now(SHOP_ZONE);
    LocalDate rangeFrom = from != null ? from : rangeTo.withDayOfMonth(1);

    Instant fromInstant = rangeFrom.atStartOfDay(SHOP_ZONE).toInstant();
    Instant toInstantExclusive = rangeTo.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();

    // Completed sales in period + all prior (needed for opening balance & against-ref resolution).
    Map<String, Purchase> saleById = new LinkedHashMap<>();
    for (Purchase sale :
        purchaseRepository.findByShopIdAndStatusAndSoldAtBetween(
            shopId, PurchaseStatus.COMPLETED, fromInstant, toInstantExclusive.minusNanos(1))) {
      if (sale.getId() != null) {
        saleById.putIfAbsent(sale.getId(), sale);
      }
    }
    for (Purchase sale :
        purchaseRepository.findByShopIdAndStatusAndSoldAtBetween(
            shopId, PurchaseStatus.COMPLETED, Instant.EPOCH, fromInstant.minusNanos(1))) {
      if (sale.getId() != null) {
        saleById.putIfAbsent(sale.getId(), sale);
      }
    }

    Map<String, String> customerNames = loadCustomerNames(saleById.values());
    List<PartyMoneyMisRowDto> events = new ArrayList<>();

    for (Purchase sale : saleById.values()) {
      LocalDate day = toShopDate(sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt());
      if (day == null || day.isBefore(rangeFrom) || day.isAfter(rangeTo)) {
        continue;
      }
      if (StringUtils.hasText(partyId) && !partyId.equals(partyKeyForSale(sale))) {
        continue;
      }
      events.add(toSaleRow(sale, customerNames));
    }

    List<Refund> refunds =
        refundRepository.findByShopIdAndCreatedAtBetween(
            shopId, fromInstant, toInstantExclusive.minusNanos(1));
    for (Refund ref : refunds) {
      LocalDate day = toShopDate(ref.getCreatedAt());
      if (day == null || day.isBefore(rangeFrom) || day.isAfter(rangeTo)) {
        continue;
      }
      Purchase linked = ref.getPurchaseId() != null ? saleById.get(ref.getPurchaseId()) : null;
      if (linked == null && StringUtils.hasText(ref.getPurchaseId())) {
        linked = purchaseRepository.findById(ref.getPurchaseId()).orElse(null);
        if (linked != null && linked.getId() != null) {
          saleById.put(linked.getId(), linked);
        }
      }
      String customerId = refundPartyKey(ref, linked);
      if (StringUtils.hasText(partyId) && !partyId.equals(customerId)) {
        continue;
      }
      events.add(toSalesReturnRow(ref, linked, customerId, customerNames));
    }

    List<CreditEntryType> creditTypes =
        Arrays.asList(CreditEntryType.SETTLEMENT, CreditEntryType.CHARGE, CreditEntryType.ADJUSTMENT);
    Map<String, CreditEntry> creditById = new LinkedHashMap<>();
    for (CreditEntry entry :
        creditEntryRepository.findByShopIdAndPartyTypeAndEntryTypeInAndTxnDateBetween(
            shopId, CreditPartyType.CUSTOMER, creditTypes, rangeFrom, rangeTo)) {
      if (entry.getId() != null) {
        creditById.put(entry.getId(), entry);
      }
    }
    for (CreditEntry entry :
        creditEntryRepository.findByShopIdAndPartyTypeAndCreatedAtBetween(
            shopId, CreditPartyType.CUSTOMER, fromInstant, toInstantExclusive.minusNanos(1))) {
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
      if (StringUtils.hasText(partyId) && !partyId.equals(entry.getPartyRefId())) {
        continue;
      }
      // Skip auto charges that duplicate the credit leg already captured on the sale row.
      if (entry.getEntryType() == CreditEntryType.CHARGE
          && entry.getSourceKey() != null
          && entry.getSourceKey().startsWith("SALE:CREDIT:")) {
        continue;
      }
      events.add(toCustomerCreditRow(entry, customerNames));
    }

    Map<String, BigDecimal> openingByParty =
        computeCustomerOpeningBalances(shopId, rangeFrom, partyId, saleById);

    events = filterEvents(events, txnTypes, moneyFilter, q);

    events.sort(
        Comparator.comparing(PartyMoneyMisRowDto::getTxnDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PartyMoneyMisRowDto::getPostedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                r -> r.getPartyName() != null ? r.getPartyName().toLowerCase(Locale.ROOT) : "",
                Comparator.nullsLast(Comparator.naturalOrder())));

    List<PartyMoneyMisRowDto> withBalance =
        applyRunningBalances(events, openingByParty, customerNames);

    if (withBalance.size() > MAX_ROWS) {
      withBalance = new ArrayList<>(withBalance.subList(0, MAX_ROWS));
    }

    PartyMoneyMisSummaryDto summary = buildSummary(withBalance, openingByParty, customerNames);

    return PartyMoneyMisResponse.builder()
        .side("CUSTOMER")
        .from(rangeFrom)
        .to(rangeTo)
        .rows(withBalance)
        .summary(summary)
        .build();
  }

  private String partyKeyForSale(Purchase sale) {
    return StringUtils.hasText(sale.getCustomerId()) ? sale.getCustomerId() : WALKIN_PARTY_ID;
  }

  private String refundPartyKey(Refund ref, Purchase linked) {
    if (StringUtils.hasText(ref.getCustomerId())) {
      return ref.getCustomerId();
    }
    if (linked != null && StringUtils.hasText(linked.getCustomerId())) {
      return linked.getCustomerId();
    }
    return WALKIN_PARTY_ID;
  }

  private Map<String, String> loadCustomerNames(Collection<Purchase> sales) {
    Map<String, String> map = new HashMap<>();
    map.put(WALKIN_PARTY_ID, WALKIN_PARTY_NAME);
    for (Purchase sale : sales) {
      String pid = partyKeyForSale(sale);
      if (WALKIN_PARTY_ID.equals(pid) || map.containsKey(pid)) {
        continue;
      }
      if (StringUtils.hasText(sale.getCustomerName())) {
        map.put(pid, sale.getCustomerName());
        continue;
      }
      try {
        customerRepository
            .findById(pid)
            .ifPresent(c -> map.put(c.getId(), c.getName() != null ? c.getName() : c.getId()));
      } catch (Exception e) {
        log.warn("Could not load customer {}: {}", pid, e.getMessage());
      }
      map.putIfAbsent(pid, pid);
    }
    return map;
  }

  private Map<String, BigDecimal> computeCustomerOpeningBalances(
      String shopId, LocalDate from, String partyId, Map<String, Purchase> saleById) {
    Map<String, BigDecimal> opening = new HashMap<>();
    Instant fromInstant = from.atStartOfDay(SHOP_ZONE).toInstant();

    for (Purchase sale : saleById.values()) {
      LocalDate day = toShopDate(sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt());
      if (day == null || !day.isBefore(from)) {
        continue;
      }
      String pid = partyKeyForSale(sale);
      if (StringUtils.hasText(partyId) && !partyId.equals(pid)) {
        continue;
      }
      VendorPurchasePaymentBreakdown.Result tender = tenderForSale(sale);
      addDelta(opening, pid, tender.creditAmount());
    }

    List<Refund> priorRefunds =
        refundRepository.findByShopIdAndCreatedAtBetween(
            shopId, Instant.EPOCH, fromInstant.minusNanos(1));
    for (Refund ref : priorRefunds) {
      Purchase linked = ref.getPurchaseId() != null ? saleById.get(ref.getPurchaseId()) : null;
      String pid = refundPartyKey(ref, linked);
      if (!StringUtils.hasText(pid)) {
        continue;
      }
      if (StringUtils.hasText(partyId) && !partyId.equals(pid)) {
        continue;
      }
      BigDecimal creditLeg = nz(ref.getRefundToCredit());
      if (creditLeg.signum() == 0 && isAllZeroRefund(ref)) {
        creditLeg = nz(ref.getRefundAmount());
      }
      // Sales returns reduce receivable.
      addDelta(opening, pid, creditLeg.negate());
    }

    List<CreditEntry> priorCredits =
        creditEntryRepository.findByShopIdAndPartyTypeAndTxnDateBetween(
            shopId, CreditPartyType.CUSTOMER, LocalDate.of(1970, 1, 1), from.minusDays(1));
    for (CreditEntry entry : priorCredits) {
      if (StringUtils.hasText(partyId) && !partyId.equals(entry.getPartyRefId())) {
        continue;
      }
      if (entry.getEntryType() == CreditEntryType.CHARGE
          && entry.getSourceKey() != null
          && entry.getSourceKey().startsWith("SALE:CREDIT:")) {
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

  private PartyMoneyMisRowDto toSaleRow(Purchase sale, Map<String, String> customerNames) {
    VendorPurchasePaymentBreakdown.Result tender = tenderForSale(sale);
    Instant posted = sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt();
    String pid = partyKeyForSale(sale);
    BigDecimal total =
        nz(sale.getGrandTotal()).signum() > 0
            ? sale.getGrandTotal()
            : tender.paidAmount().add(tender.creditAmount());
    String fallbackName = StringUtils.hasText(sale.getCustomerName()) ? sale.getCustomerName() : pid;
    return PartyMoneyMisRowDto.builder()
        .txnId("SSAL-" + shortId(sale.getId()))
        .txnType("SALE")
        .txnTypeLabel("Sale")
        .partyId(pid)
        .partyName(customerNames.getOrDefault(pid, fallbackName))
        .txnDate(toShopDate(sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt()))
        .postedAt(posted)
        .refNo(sale.getInvoiceNo())
        .totalAmount(scale(total))
        .cashAmount(tender.cashAmount())
        .onlineAmount(tender.onlineAmount())
        .creditAmount(tender.creditAmount())
        .sourceType("SALE_INVOICE")
        .sourceId(sale.getId())
        .opening(false)
        .build();
  }

  private PartyMoneyMisRowDto toSalesReturnRow(
      Refund ref, Purchase linked, String customerId, Map<String, String> customerNames) {
    BigDecimal total = nz(ref.getRefundAmount()).negate();
    BigDecimal cash = nz(ref.getRefundCash()).negate();
    BigDecimal online = nz(ref.getRefundOnline()).negate();
    BigDecimal credit = nz(ref.getRefundToCredit()).negate();
    if (cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0) {
      credit = total;
    }
    return PartyMoneyMisRowDto.builder()
        .txnId("SRET-" + shortId(ref.getId()))
        .txnType("SALES_RETURN")
        .txnTypeLabel("Sales return")
        .partyId(customerId)
        .partyName(customerNames.getOrDefault(customerId, customerId))
        .txnDate(toShopDate(ref.getCreatedAt()))
        .postedAt(ref.getCreatedAt())
        .refNo(ref.getCreditNoteNo())
        .againstTxnId(linked != null ? "SSAL-" + shortId(linked.getId()) : null)
        .againstRefNo(linked != null ? linked.getInvoiceNo() : null)
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceType("SALES_RETURN")
        .sourceId(ref.getId())
        .opening(false)
        .build();
  }

  private PartyMoneyMisRowDto toCustomerCreditRow(
      CreditEntry entry, Map<String, String> customerNames) {
    boolean settlement = entry.getEntryType() == CreditEntryType.SETTLEMENT;
    String txnType = settlement ? "CUSTOMER_RECEIPT" : "CUSTOMER_CREDIT_CHARGE";
    String label = settlement ? "Receipt" : "Credit charge";
    String prefix = settlement ? "SRCT-" : "SCHG-";
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
    return PartyMoneyMisRowDto.builder()
        .txnId(prefix + shortId(entry.getId()))
        .txnType(txnType)
        .txnTypeLabel(label)
        .partyId(entry.getPartyRefId())
        .partyName(customerNames.getOrDefault(entry.getPartyRefId(), entry.getPartyRefId()))
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
        .sourceType(settlement ? "CUSTOMER_SETTLEMENT" : "CUSTOMER_CREDIT_CHARGE")
        .sourceId(entry.getId())
        .opening(false)
        .build();
  }

  private VendorPurchasePaymentBreakdown.Result tenderForSale(Purchase sale) {
    BigDecimal total = nz(sale.getGrandTotal());
    if (total.signum() <= 0) {
      total = nz(sale.getSubTotal()).add(nz(sale.getTaxTotal())).subtract(nz(sale.getDiscountTotal()));
    }
    if (sale.getCashAmount() != null
        || sale.getOnlineAmount() != null
        || sale.getCreditAmount() != null) {
      return VendorPurchasePaymentBreakdown.resolve(
          total,
          sale.getPaymentMethod(),
          null,
          sale.getCashAmount(),
          sale.getOnlineAmount(),
          sale.getCreditAmount());
    }
    return VendorPurchasePaymentBreakdown.deriveForReport(total, sale.getPaymentMethod(), null);
  }

  private static boolean isAllZeroRefund(Refund ref) {
    return nz(ref.getRefundCash()).signum() == 0
        && nz(ref.getRefundOnline()).signum() == 0
        && nz(ref.getRefundToCredit()).signum() == 0;
  }

  private Map<String, BigDecimal> computeOpeningBalances(
      String shopId,
      LocalDate from,
      String partyId,
      Map<String, String> vendorNames,
      Map<String, VendorPurchaseInvoice> invoiceById) {
    Map<String, BigDecimal> opening = new HashMap<>();
    Instant fromInstant = from.atStartOfDay(SHOP_ZONE).toInstant();

    for (VendorPurchaseInvoice inv : invoiceById.values()) {
      LocalDate day = toShopDate(inv.getInvoiceDate() != null ? inv.getInvoiceDate() : inv.getCreatedAt());
      if (day == null || !day.isBefore(from)) {
        continue;
      }
      if (StringUtils.hasText(partyId) && !partyId.equals(inv.getVendorId())) {
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
      String vendorId = linked != null ? linked.getVendorId() : null;
      if (!StringUtils.hasText(vendorId)) {
        continue;
      }
      if (StringUtils.hasText(partyId) && !partyId.equals(vendorId)) {
        continue;
      }
      BigDecimal creditLeg = nz(ret.getRefundToCredit());
      if (creditLeg.signum() == 0 && isAllZeroRefund(ret)) {
        creditLeg = nz(ret.getReturnAmount());
      }
      // Returns reduce payable
      addDelta(opening, vendorId, creditLeg.negate());
    }

    List<CreditEntry> priorCredits =
        creditEntryRepository.findByShopIdAndPartyTypeAndTxnDateBetween(
            shopId, CreditPartyType.VENDOR, LocalDate.of(1970, 1, 1), from.minusDays(1));
    for (CreditEntry entry : priorCredits) {
      if (StringUtils.hasText(partyId) && !partyId.equals(entry.getPartyRefId())) {
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

  private List<PartyMoneyMisRowDto> applyRunningBalances(
      List<PartyMoneyMisRowDto> events,
      Map<String, BigDecimal> openingByParty,
      Map<String, String> vendorNames) {
    Map<String, BigDecimal> running = new HashMap<>();
    for (Map.Entry<String, BigDecimal> e : openingByParty.entrySet()) {
      running.put(e.getKey(), scale(e.getValue()));
    }

    List<PartyMoneyMisRowDto> out = new ArrayList<>();
    Set<String> opened = new HashSet<>();

    for (PartyMoneyMisRowDto row : events) {
      String pid = row.getPartyId() != null ? row.getPartyId() : "";
      if (!opened.contains(pid) && openingByParty.containsKey(pid) && openingByParty.get(pid).signum() != 0) {
        BigDecimal open = scale(openingByParty.get(pid));
        running.put(pid, open);
        out.add(
            PartyMoneyMisRowDto.builder()
                .txnId("OPEN-" + shortId(pid))
                .txnType("OPENING")
                .txnTypeLabel("Opening")
                .partyId(pid)
                .partyName(vendorNames.getOrDefault(pid, row.getPartyName()))
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

  private BigDecimal partyDelta(PartyMoneyMisRowDto row) {
    String type = row.getTxnType();
    // Vendor payable and customer receivable share sign conventions: a purchase/sale (credit leg)
    // or a credit charge increases the balance owed; a payment/receipt or a return reduces it.
    if ("VENDOR_PURCHASE".equals(type)
        || "VENDOR_CREDIT_CHARGE".equals(type)
        || "SALE".equals(type)
        || "CUSTOMER_CREDIT_CHARGE".equals(type)
        || "OPENING".equals(type)) {
      return nz(row.getCreditAmount());
    }
    if ("VENDOR_PAYMENT".equals(type) || "CUSTOMER_RECEIPT".equals(type)) {
      // Settlement/receipt reduces balance; amount is in cash/online (or total)
      BigDecimal paid = nz(row.getCashAmount()).add(nz(row.getOnlineAmount()));
      if (paid.signum() == 0) {
        paid = nz(row.getTotalAmount()).abs();
      }
      return paid.negate();
    }
    if ("VENDOR_RETURN".equals(type) || "SALES_RETURN".equals(type)) {
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

  private PartyMoneyMisSummaryDto buildSummary(
      List<PartyMoneyMisRowDto> rows,
      Map<String, BigDecimal> openingByParty,
      Map<String, String> vendorNames) {
    BigDecimal cash = zero();
    BigDecimal online = zero();
    BigDecimal credit = zero();
    BigDecimal purchase = zero();
    Map<String, PartyMoneyMisRowDto> lastByParty = new LinkedHashMap<>();

    for (PartyMoneyMisRowDto row : rows) {
      if (row.isOpening()) {
        continue;
      }
      cash = cash.add(nz(row.getCashAmount()));
      online = online.add(nz(row.getOnlineAmount()));
      credit = credit.add(nz(row.getCreditAmount()));
      if ("VENDOR_PURCHASE".equals(row.getTxnType()) || "SALE".equals(row.getTxnType())) {
        purchase = purchase.add(nz(row.getTotalAmount()));
      }
      if (row.getPartyId() != null) {
        lastByParty.put(row.getPartyId(), row);
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

    List<PartyMoneyMisPartySummaryDto> partySummaries = new ArrayList<>();
    Set<String> allParties = new HashSet<>(openingByParty.keySet());
    allParties.addAll(lastByParty.keySet());
    for (String pid : allParties) {
      BigDecimal open = scale(openingByParty.getOrDefault(pid, zero()));
      PartyMoneyMisRowDto last = lastByParty.get(pid);
      BigDecimal closing = last != null ? nz(last.getBalanceAfter()) : open;
      partySummaries.add(
          PartyMoneyMisPartySummaryDto.builder()
              .partyId(pid)
              .partyName(vendorNames.getOrDefault(pid, pid))
              .openingBalance(open)
              .closingBalanceInPeriod(closing)
              .currentBalance(closing)
              .build());
    }
    partySummaries.sort(
        Comparator.comparing(
            p -> p.getPartyName() != null ? p.getPartyName().toLowerCase(Locale.ROOT) : "",
            Comparator.nullsLast(Comparator.naturalOrder())));

    return PartyMoneyMisSummaryDto.builder()
        .openingBalanceTotal(openingTotal)
        .periodCashTotal(scale(cash))
        .periodOnlineTotal(scale(online))
        .periodCreditTotal(scale(credit))
        .periodPurchaseTotal(scale(purchase))
        .currentPayableTotal(scale(currentPayable))
        .partySummaries(partySummaries)
        .build();
  }

  private List<PartyMoneyMisRowDto> filterEvents(
      List<PartyMoneyMisRowDto> events, Set<String> txnTypes, String moneyFilter, String q) {
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
                return contains(r.getPartyName(), needle)
                    || contains(r.getRefNo(), needle)
                    || contains(r.getTxnId(), needle)
                    || contains(r.getAgainstRefNo(), needle);
              }
              return true;
            })
        .collect(Collectors.toList());
  }

  private boolean matchesMoneyFilter(PartyMoneyMisRowDto r, String moneyFilter) {
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

  private PartyMoneyMisRowDto toPurchaseRow(
      VendorPurchaseInvoice inv, Map<String, String> vendorNames) {
    VendorPurchasePaymentBreakdown.Result tender = tenderForInvoice(inv);
    Instant posted = inv.getCreatedAt() != null ? inv.getCreatedAt() : inv.getInvoiceDate();
    return PartyMoneyMisRowDto.builder()
        .txnId("VPUR-" + shortId(inv.getId()))
        .txnType("VENDOR_PURCHASE")
        .txnTypeLabel("Purchase")
        .partyId(inv.getVendorId())
        .partyName(vendorNames.getOrDefault(inv.getVendorId(), inv.getVendorId()))
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

  private PartyMoneyMisRowDto toReturnRow(
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
    return PartyMoneyMisRowDto.builder()
        .txnId("VRET-" + shortId(ret.getId()))
        .txnType("VENDOR_RETURN")
        .txnTypeLabel("Return")
        .partyId(vendorId)
        .partyName(vendorNames.getOrDefault(vendorId, vendorId))
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

  private PartyMoneyMisRowDto toCreditRow(CreditEntry entry, Map<String, String> vendorNames) {
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
    return PartyMoneyMisRowDto.builder()
        .txnId(prefix + shortId(entry.getId()))
        .txnType(txnType)
        .txnTypeLabel(label)
        .partyId(entry.getPartyRefId())
        .partyName(vendorNames.getOrDefault(entry.getPartyRefId(), entry.getPartyRefId()))
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

  private void addDelta(Map<String, BigDecimal> map, String partyId, BigDecimal delta) {
    if (!StringUtils.hasText(partyId) || delta == null) {
      return;
    }
    map.merge(partyId, scale(delta), BigDecimal::add);
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
}
