package com.inventory.taxation.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.taxation.domain.gstr2.*;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.utils.GstStateCode;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates inventory (inward supply) data for a period into GSTR-2 report context.
 */
@Service
@Slf4j
public class Gstr2DataAggregator {

  @Autowired
  private InventoryRepository inventoryRepository;
  @Autowired
  private VendorRepository vendorRepository;
  @Autowired
  private PricingRepository pricingRepository;
  @Autowired
  private ShopRepository shopRepository;
  @Autowired
  private VendorPurchaseReturnRepository vendorPurchaseReturnRepository;
  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  public Gstr2ReportContext buildContext(String shopId, String period) {
    Shop shop = shopRepository.findById(shopId)
        .orElseThrow(() -> new IllegalArgumentException("Shop not found: " + shopId));

    int year;
    int month;
    try {
      String[] parts = period.split("-");
      year = Integer.parseInt(parts[0]);
      month = Integer.parseInt(parts[1]);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid period, use YYYY-MM: " + period);
    }

    Instant rangeStart = LocalDate.of(year, month, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant rangeEnd = LocalDate.of(year, month, 1).plusMonths(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1);

    List<VendorPurchaseReturn> vendorReturns =
        vendorPurchaseReturnRepository.findByShopIdAndCreatedAtBetween(shopId, rangeStart, rangeEnd);

    // The supplier's own invoices for the period, where the shop has them. They
    // state what was bought; stock states what is left, and the two stop being
    // the same figure the moment anything is sold. A month whose invoices are
    // recorded is therefore reported from them and not from stock at all --
    // mixing the two would count the same goods under both.
    List<VendorPurchaseInvoice> purchaseInvoices =
        vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceDateBetween(
                shopId, rangeStart, rangeEnd)
            .stream()
            .filter(this::statesItsAmounts)
            .toList();

    List<Inventory> inventories = inventoryRepository.findByShopIdAndCreatedAtBetween(shopId, rangeStart, rangeEnd);
    inventories = inventories.stream().filter(inv -> inv.getVendorId() != null).toList();

    if (purchaseInvoices.isEmpty() && inventories.isEmpty() && vendorReturns.isEmpty()) {
      return buildEmptyContext(shopId, shop, period, year, month);
    }

    // Inward supply: the recipient is this shop, so its own state is the place
    // of supply and that part was already right. It was emitted as a bare name
    // ("Bihar"), and the portal accepts only the code-prefixed form ("10-Bihar").
    String placeOfSupply = shop.getLocation() != null
        && StringUtils.hasText(shop.getLocation().getState())
        ? GstStateCode.format(shop.getLocation().getState())
        : "";

    List<Gstr2CdnrLine> cdnrFromReturns = new ArrayList<>();
    List<Gstr2CdnurLine> cdnurFromReturns = new ArrayList<>();
    appendVendorReturnCdnLines(shopId, vendorReturns, placeOfSupply, cdnrFromReturns, cdnurFromReturns);

    if (!purchaseInvoices.isEmpty()) {
      return buildFromPurchaseInvoices(shopId, shop, period, year, month,
          purchaseInvoices, placeOfSupply, cdnrFromReturns, cdnurFromReturns);
    }

    if (inventories.isEmpty()) {
      return Gstr2ReportContext.builder()
          .shopId(shopId)
          .shopGstin(shop.getGstinNo() != null ? shop.getGstinNo() : "")
          .period(period)
          .year(year)
          .month(month)
          .b2bLines(new ArrayList<>())
          .b2burLines(new ArrayList<>())
          .impsLines(new ArrayList<>())
          .impgLines(new ArrayList<>())
          .cdnrLines(cdnrFromReturns)
          .cdnurLines(cdnurFromReturns)
          .atLines(new ArrayList<>())
          .atadjLines(new ArrayList<>())
          .exempLines(buildDefaultExempLines())
          .itcrLines(new ArrayList<>())
          .hsnLines(new ArrayList<>())
          .build();
    }

    Set<String> vendorIds = inventories.stream()
        .map(Inventory::getVendorId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    Map<String, Vendor> vendorMap = vendorIds.isEmpty() ? Map.of()
        : vendorRepository.findAllById(vendorIds).stream().collect(Collectors.toMap(Vendor::getId, v -> v));

    Set<String> pricingIds = inventories.stream()
        .map(Inventory::getPricingId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    Map<String, Pricing> pricingMap = pricingIds.isEmpty() ? Map.of()
        : pricingRepository.findAllById(pricingIds).stream().collect(Collectors.toMap(Pricing::getId, p -> p));

    Set<String> purchaseInvoiceDocIds = new HashSet<>();
    for (Inventory inv : inventories) {
      if (StringUtils.hasText(inv.getVendorPurchaseInvoiceId())) {
        purchaseInvoiceDocIds.add(inv.getVendorPurchaseInvoiceId().trim());
      }
      if (StringUtils.hasText(inv.getLotId())) {
        purchaseInvoiceDocIds.add(inv.getLotId().trim());
      }
    }
    Map<String, VendorPurchaseInvoice> purchaseInvoiceById =
        purchaseInvoiceDocIds.isEmpty()
            ? Map.of()
            : vendorPurchaseInvoiceRepository.findAllById(purchaseInvoiceDocIds).stream()
                .filter(vpi -> shopId.equals(vpi.getShopId()))
                .collect(Collectors.toMap(VendorPurchaseInvoice::getId, v -> v, (a, b) -> a));

    // Group by lotId + vendorId as a pseudo-invoice (one vendor batch)
    Map<String, List<Inventory>> byLotVendor = inventories.stream()
        .collect(Collectors.groupingBy(inv -> (inv.getLotId() != null ? inv.getLotId() : inv.getId()) + "|" + (inv.getVendorId() != null ? inv.getVendorId() : "")));

    List<Gstr2B2bLine> b2bLines = new ArrayList<>();
    List<Gstr2B2burLine> b2burLines = new ArrayList<>();
    Map<String, GstHsnLine> hsnMap = new LinkedHashMap<>();

    for (Map.Entry<String, List<Inventory>> entry : byLotVendor.entrySet()) {
      List<Inventory> lotItems = entry.getValue();
      if (lotItems.isEmpty()) continue;
      Inventory first = lotItems.get(0);
      Vendor vendor = first.getVendorId() != null ? vendorMap.get(first.getVendorId()) : null;
      boolean isRegistered = vendor != null && StringUtils.hasText(vendor.getGstinUin());
      String supplierGstin = vendor != null ? (vendor.getGstinUin() != null ? vendor.getGstinUin() : "") : "";
      String supplierName = vendor != null ? (vendor.getCompanyName() != null ? vendor.getCompanyName() : vendor.getName()) : "Unknown";
      if (supplierName == null) supplierName = "Unknown";

      BigDecimal totalInvoiceValue = BigDecimal.ZERO;
      BigDecimal totalTaxableValue = BigDecimal.ZERO;
      BigDecimal totalCgst = BigDecimal.ZERO;
      BigDecimal totalSgst = BigDecimal.ZERO;
      BigDecimal totalIgst = BigDecimal.ZERO;
      BigDecimal totalCess = BigDecimal.ZERO;
      String rateStr = "0";
      LocalDate invDate = null;

      for (Inventory inv : lotItems) {
        Pricing pricing = inv.getPricingId() != null ? pricingMap.get(inv.getPricingId()) : null;
        BigDecimal costPrice = pricing != null ? pricing.getCostPrice() : null;
        if (costPrice == null) costPrice = BigDecimal.ZERO;
        String sgstStr = pricing != null ? pricing.getSgst() : null;
        String cgstStr = pricing != null ? pricing.getCgst() : null;
        if (!StringUtils.hasText(sgstStr)) sgstStr = "0";
        if (!StringUtils.hasText(cgstStr)) cgstStr = "0";
        BigDecimal sgstRate = parseRate(sgstStr);
        BigDecimal cgstRate = parseRate(cgstStr);
        BigDecimal rate = sgstRate.add(cgstRate);
        rateStr = rate.stripTrailingZeros().toPlainString();

        int qty = inv.getReceivedBaseCount() != null ? inv.getReceivedBaseCount() : 1;
        if (qty <= 0) qty = 1;
        BigDecimal taxableVal = costPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cgstAmt = taxableVal.multiply(cgstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal sgstAmt = taxableVal.multiply(sgstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal invValue = taxableVal.add(cgstAmt).add(sgstAmt);

        totalInvoiceValue = totalInvoiceValue.add(invValue);
        totalTaxableValue = totalTaxableValue.add(taxableVal);
        totalCgst = totalCgst.add(cgstAmt);
        totalSgst = totalSgst.add(sgstAmt);

        if (invDate == null) {
          Instant ts = inv.getReceivedDate() != null ? inv.getReceivedDate() : inv.getCreatedAt();
          invDate = ts != null ? LocalDateTime.ofInstant(ts, ZoneId.systemDefault()).toLocalDate() : LocalDate.now();
        }

        String hsn = inv.getHsn() != null && !inv.getHsn().isBlank() ? inv.getHsn() : "0";
        String desc = inv.getDescription() != null ? inv.getDescription() : (inv.getName() != null ? inv.getName() : "");
        String key = hsn + "|" + rate;
        GstHsnLine existing = hsnMap.get(key);
        if (existing == null) {
          existing = GstHsnLine.builder()
              .hsn(hsn)
              .description(desc)
              .uqc("OTH-OTHERS")
              .totalQuantity(BigDecimal.valueOf(qty))
              .totalValue(taxableVal)
              .rate(rate)
              .taxableValue(taxableVal)
              .integratedTaxAmount(BigDecimal.ZERO)
              .centralTaxAmount(cgstAmt)
              .stateUtTaxAmount(sgstAmt)
              .cessAmount(BigDecimal.ZERO)
              .b2b(isRegistered)
              .build();
          hsnMap.put(key, existing);
        } else {
          existing.setTotalQuantity(existing.getTotalQuantity().add(BigDecimal.valueOf(qty)));
          existing.setTotalValue(existing.getTotalValue().add(taxableVal));
          existing.setTaxableValue(existing.getTaxableValue().add(taxableVal));
          existing.setCentralTaxAmount(existing.getCentralTaxAmount().add(cgstAmt));
          existing.setStateUtTaxAmount(existing.getStateUtTaxAmount().add(sgstAmt));
        }
      }

      String invoiceNo = resolveGstrPurchaseInvoiceDisplayNo(first, purchaseInvoiceById);
      if (invDate == null) invDate = LocalDate.now();

      if (isRegistered) {
        Gstr2B2bLine line = Gstr2B2bLine.builder()
            .supplierGstin(supplierGstin)
            .invoiceNo(invoiceNo)
            .invoiceDate(invDate)
            .invoiceValue(totalInvoiceValue)
            .placeOfSupply(placeOfSupply)
            .reverseCharge("N")
            .invoiceType("Regular")
            .rate(parseRate(rateStr))
            .taxableValue(totalTaxableValue)
            .integratedTaxPaid(totalIgst)
            .centralTaxPaid(totalCgst)
            .stateUtTaxPaid(totalSgst)
            .cessAmount(totalCess)
            .itcEligibility("Inputs")
            .availedItcIntegrated(totalIgst)
            .availedItcCentral(totalCgst)
            .availedItcStateUt(totalSgst)
            .availedItcCess(totalCess)
            .build();
        b2bLines.add(line);
      } else {
        Gstr2B2burLine line = Gstr2B2burLine.builder()
            .supplierName(supplierName)
            .invoiceNo(invoiceNo)
            .invoiceDate(invDate)
            .invoiceValue(totalInvoiceValue)
            .placeOfSupply(placeOfSupply)
            .supplyType("Intra State")
            .rate(parseRate(rateStr))
            .taxableValue(totalTaxableValue)
            .integratedTaxPaid(totalIgst)
            .centralTaxPaid(totalCgst)
            .stateUtTaxPaid(totalSgst)
            .cessAmount(totalCess)
            .itcEligibility("Inputs")
            .availedItcIntegrated(totalIgst)
            .availedItcCentral(totalCgst)
            .availedItcStateUt(totalSgst)
            .availedItcCess(totalCess)
            .build();
        b2burLines.add(line);
      }
    }

    // Oldest first. The lines come out in whatever order the inventory query
    // returned, which is neither the order the invoices were received nor any
    // order a reader can follow when reconciling against a supplier statement.
    // Invoice number breaks ties so the sequence is stable between runs.
    Comparator<LocalDate> byDate = Comparator.nullsLast(Comparator.naturalOrder());
    b2bLines.sort(Comparator.comparing(Gstr2B2bLine::getInvoiceDate, byDate)
        .thenComparing(l -> l.getInvoiceNo() == null ? "" : l.getInvoiceNo()));
    b2burLines.sort(Comparator.comparing(Gstr2B2burLine::getInvoiceDate, byDate)
        .thenComparing(l -> l.getInvoiceNo() == null ? "" : l.getInvoiceNo()));

    return Gstr2ReportContext.builder()
        .shopId(shopId)
        .shopGstin(shop.getGstinNo() != null ? shop.getGstinNo() : "")
        .period(period)
        .year(year)
        .month(month)
        .b2bLines(b2bLines)
        .b2burLines(b2burLines)
        .impsLines(new ArrayList<>())
        .impgLines(new ArrayList<>())
        .cdnrLines(cdnrFromReturns)
        .cdnurLines(cdnurFromReturns)
        .atLines(new ArrayList<>())
        .atadjLines(new ArrayList<>())
        .exempLines(buildDefaultExempLines())
        .itcrLines(new ArrayList<>())
        .hsnLines(new ArrayList<>(hsnMap.values()))
        .build();
  }

  /**
   * Whether an invoice carries the amounts the return needs.
   *
   * <p>Invoices exist that were created only to give a stock lot a number to
   * display, with no total and a placeholder line. Reporting from those would
   * replace real figures with nothing, so they are left to the stock path.
   */
  private boolean statesItsAmounts(VendorPurchaseInvoice invoice) {
    return invoice.getInvoiceTotal() != null
        && invoice.getInvoiceTotal().compareTo(BigDecimal.ZERO) > 0
        && invoice.getLines() != null
        && invoice.getLines().stream().anyMatch(
            line -> line.getTaxableValue() != null || line.getTotalAmount() != null);
  }

  /** A line's tax rate, from the rates the invoice recorded for it. */
  private BigDecimal lineRate(VendorPurchaseInvoiceLine line) {
    return parseRate(line.getSgst()).add(parseRate(line.getCgst()));
  }

  /**
   * A line's value before tax, backed out of the tax-inclusive total when the
   * invoice did not state it directly.
   */
  private BigDecimal lineTaxable(VendorPurchaseInvoiceLine line) {
    if (line.getTaxableValue() != null) return line.getTaxableValue();
    BigDecimal gross = line.getTotalAmount() != null ? line.getTotalAmount() : BigDecimal.ZERO;
    BigDecimal rate = lineRate(line);
    if (rate.compareTo(BigDecimal.ZERO) <= 0) return gross;
    return gross.multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
  }

  /**
   * The inward return built from the supplier invoices themselves.
   *
   * <p>Reported per rate, as the portal expects: an invoice carrying goods at
   * two rates is two rows, each with its own taxable value and tax, and the
   * invoice value repeated on both because it belongs to the invoice rather
   * than to a rate.
   */
  private Gstr2ReportContext buildFromPurchaseInvoices(
      String shopId, Shop shop, String period, int year, int month,
      List<VendorPurchaseInvoice> invoices, String placeOfSupply,
      List<Gstr2CdnrLine> cdnrFromReturns, List<Gstr2CdnurLine> cdnurFromReturns) {

    Set<String> vendorIds = invoices.stream()
        .map(VendorPurchaseInvoice::getVendorId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    Map<String, Vendor> vendorMap = vendorIds.isEmpty() ? Map.of()
        : vendorRepository.findAllById(vendorIds).stream()
            .collect(Collectors.toMap(Vendor::getId, v -> v));

    List<Gstr2B2bLine> b2bLines = new ArrayList<>();
    List<Gstr2B2burLine> b2burLines = new ArrayList<>();
    Map<String, GstHsnLine> hsnMap = new LinkedHashMap<>();

    for (VendorPurchaseInvoice invoice : invoices) {
      Vendor vendor = invoice.getVendorId() != null
          ? vendorMap.get(invoice.getVendorId()) : null;
      boolean registered = vendor != null && StringUtils.hasText(vendor.getGstinUin());
      String supplierGstin = registered ? vendor.getGstinUin() : "";
      String supplierName = vendor == null ? "Unknown"
          : (StringUtils.hasText(vendor.getCompanyName())
              ? vendor.getCompanyName() : vendor.getName());
      if (supplierName == null) supplierName = "Unknown";

      LocalDate invoiceDate = invoice.getInvoiceDate() != null
          ? LocalDateTime.ofInstant(invoice.getInvoiceDate(), ZoneId.systemDefault()).toLocalDate()
          : LocalDate.now();
      BigDecimal invoiceValue = invoice.getInvoiceTotal();

      Map<String, BigDecimal[]> byRate = new LinkedHashMap<>();
      for (VendorPurchaseInvoiceLine line : invoice.getLines()) {
        BigDecimal rate = lineRate(line);
        BigDecimal taxable = lineTaxable(line);
        BigDecimal central = taxable.multiply(parseRate(line.getCgst()))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal state = taxable.multiply(parseRate(line.getSgst()))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal[] bucket = byRate.computeIfAbsent(
            rate.stripTrailingZeros().toPlainString(),
            key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        bucket[0] = bucket[0].add(taxable);
        bucket[1] = bucket[1].add(central);
        bucket[2] = bucket[2].add(state);

        String hsn = StringUtils.hasText(line.getHsn()) ? line.getHsn() : "0";
        BigDecimal quantity = BigDecimal.valueOf(
            line.getCount() != null ? line.getCount() : 0);
        BigDecimal gross = line.getTotalAmount() != null
            ? line.getTotalAmount() : taxable.add(central).add(state);
        GstHsnLine row = hsnMap.get(hsn + "|" + rate);
        if (row == null) {
          hsnMap.put(hsn + "|" + rate, GstHsnLine.builder()
              .hsn(hsn)
              .description(hsn)
              .uqc("OTH-OTHERS")
              .totalQuantity(quantity)
              // Tax inclusive, which is what the portal's own summary reports
              // under this column and what the taxable column is measured
              // against.
              .totalValue(gross)
              .rate(rate)
              .taxableValue(taxable)
              .integratedTaxAmount(BigDecimal.ZERO)
              .centralTaxAmount(central)
              .stateUtTaxAmount(state)
              .cessAmount(BigDecimal.ZERO)
              .b2b(registered)
              .build());
        } else {
          row.setTotalQuantity(row.getTotalQuantity().add(quantity));
          row.setTotalValue(row.getTotalValue().add(gross));
          row.setTaxableValue(row.getTaxableValue().add(taxable));
          row.setCentralTaxAmount(row.getCentralTaxAmount().add(central));
          row.setStateUtTaxAmount(row.getStateUtTaxAmount().add(state));
        }
      }

      for (Map.Entry<String, BigDecimal[]> entry : byRate.entrySet()) {
        BigDecimal[] bucket = entry.getValue();
        if (registered) {
          b2bLines.add(Gstr2B2bLine.builder()
              .supplierGstin(supplierGstin)
              .invoiceNo(invoice.getInvoiceNo())
              .invoiceDate(invoiceDate)
              .invoiceValue(invoiceValue)
              .placeOfSupply(placeOfSupply)
              .reverseCharge("N")
              .invoiceType("Regular")
              .rate(parseRate(entry.getKey()))
              .taxableValue(bucket[0])
              .integratedTaxPaid(BigDecimal.ZERO)
              .centralTaxPaid(bucket[1])
              .stateUtTaxPaid(bucket[2])
              .cessAmount(BigDecimal.ZERO)
              .itcEligibility("Inputs")
              .availedItcIntegrated(BigDecimal.ZERO)
              .availedItcCentral(bucket[1])
              .availedItcStateUt(bucket[2])
              .availedItcCess(BigDecimal.ZERO)
              .build());
        } else {
          b2burLines.add(Gstr2B2burLine.builder()
              .supplierName(supplierName)
              .invoiceNo(invoice.getInvoiceNo())
              .invoiceDate(invoiceDate)
              .invoiceValue(invoiceValue)
              .placeOfSupply(placeOfSupply)
              .supplyType("Intra State")
              .rate(parseRate(entry.getKey()))
              .taxableValue(bucket[0])
              .integratedTaxPaid(BigDecimal.ZERO)
              .centralTaxPaid(bucket[1])
              .stateUtTaxPaid(bucket[2])
              .cessAmount(BigDecimal.ZERO)
              .itcEligibility("Inputs")
              .availedItcIntegrated(BigDecimal.ZERO)
              .availedItcCentral(bucket[1])
              .availedItcStateUt(bucket[2])
              .availedItcCess(BigDecimal.ZERO)
              .build());
        }
      }
    }

    Comparator<LocalDate> byDate = Comparator.nullsLast(Comparator.naturalOrder());
    b2bLines.sort(Comparator.comparing(Gstr2B2bLine::getInvoiceDate, byDate)
        .thenComparing(l -> l.getInvoiceNo() == null ? "" : l.getInvoiceNo()));
    b2burLines.sort(Comparator.comparing(Gstr2B2burLine::getInvoiceDate, byDate)
        .thenComparing(l -> l.getInvoiceNo() == null ? "" : l.getInvoiceNo()));

    return Gstr2ReportContext.builder()
        .shopId(shopId)
        .shopGstin(shop.getGstinNo() != null ? shop.getGstinNo() : "")
        .period(period)
        .year(year)
        .month(month)
        .b2bLines(b2bLines)
        .b2burLines(b2burLines)
        .impsLines(new ArrayList<>())
        .impgLines(new ArrayList<>())
        .cdnrLines(cdnrFromReturns)
        .cdnurLines(cdnurFromReturns)
        .atLines(new ArrayList<>())
        .atadjLines(new ArrayList<>())
        .exempLines(buildDefaultExempLines())
        .itcrLines(new ArrayList<>())
        .hsnLines(new ArrayList<>(hsnMap.values()))
        .build();
  }

  private Gstr2ReportContext buildEmptyContext(String shopId, Shop shop, String period, int year, int month) {
    return Gstr2ReportContext.builder()
        .shopId(shopId)
        .shopGstin(shop.getGstinNo() != null ? shop.getGstinNo() : "")
        .period(period)
        .year(year)
        .month(month)
        .b2bLines(new ArrayList<>())
        .b2burLines(new ArrayList<>())
        .impsLines(new ArrayList<>())
        .impgLines(new ArrayList<>())
        .cdnrLines(new ArrayList<>())
        .cdnurLines(new ArrayList<>())
        .atLines(new ArrayList<>())
        .atadjLines(new ArrayList<>())
        .exempLines(buildDefaultExempLines())
        .itcrLines(new ArrayList<>())
        .hsnLines(new ArrayList<>())
        .build();
  }

  private List<Gstr2ExempLine> buildDefaultExempLines() {
    return List.of(
        Gstr2ExempLine.builder()
            .description("Inter-State supplies")
            .compositionTaxablePerson(BigDecimal.ZERO)
            .nilRatedSupplies(BigDecimal.ZERO)
            .exemptedOtherThanNilOrNonGst(BigDecimal.ZERO)
            .nonGstSupplies(BigDecimal.ZERO)
            .build(),
        Gstr2ExempLine.builder()
            .description("Intra-State supplies")
            .compositionTaxablePerson(BigDecimal.ZERO)
            .nilRatedSupplies(BigDecimal.ZERO)
            .exemptedOtherThanNilOrNonGst(BigDecimal.ZERO)
            .nonGstSupplies(BigDecimal.ZERO)
            .build()
    );
  }

  /**
   * Prefer human-readable supplier invoice number from {@link VendorPurchaseInvoice}; inventory
   * {@code lotId} is usually the invoice document id after stock-in migration.
   */
  private String resolveGstrPurchaseInvoiceDisplayNo(
      Inventory first, Map<String, VendorPurchaseInvoice> purchaseInvoiceById) {
    String docId =
        StringUtils.hasText(first.getVendorPurchaseInvoiceId())
            ? first.getVendorPurchaseInvoiceId().trim()
            : null;
    if (docId == null && StringUtils.hasText(first.getLotId())) {
      docId = first.getLotId().trim();
    }
    if (docId != null) {
      VendorPurchaseInvoice invoice = purchaseInvoiceById.get(docId);
      if (invoice != null && StringUtils.hasText(invoice.getInvoiceNo())) {
        return invoice.getInvoiceNo().trim();
      }
    }
    if (StringUtils.hasText(first.getLotId())) {
      return first.getLotId().trim();
    }
    return "INV-" + first.getId();
  }

  private BigDecimal parseRate(String rateStr) {
    if (!StringUtils.hasText(rateStr)) return BigDecimal.ZERO;
    try {
      return new BigDecimal(rateStr.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * Maps recorded vendor invoice returns into GSTR-2 CDNR (supplier with GSTIN) / CDNUR (otherwise).
   */
  private void appendVendorReturnCdnLines(
      String shopId,
      List<VendorPurchaseReturn> returns,
      String placeOfSupply,
      List<Gstr2CdnrLine> outCdnr,
      List<Gstr2CdnurLine> outCdnur) {
    if (returns == null || returns.isEmpty()) {
      return;
    }
    Set<String> invoiceIds = returns.stream()
        .map(VendorPurchaseReturn::getVendorPurchaseInvoiceId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    if (invoiceIds.isEmpty()) {
      return;
    }
    Map<String, VendorPurchaseInvoice> invoiceMap = vendorPurchaseInvoiceRepository.findAllById(invoiceIds)
        .stream()
        .filter(inv -> shopId.equals(inv.getShopId()))
        .collect(Collectors.toMap(VendorPurchaseInvoice::getId, inv -> inv));

    Set<String> vendorIds = invoiceMap.values().stream()
        .map(VendorPurchaseInvoice::getVendorId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    Map<String, Vendor> vendorMap =
        vendorIds.isEmpty()
            ? Map.of()
            : vendorRepository.findAllById(vendorIds).stream()
                .collect(Collectors.toMap(Vendor::getId, v -> v));

    for (VendorPurchaseReturn vr : returns) {
      VendorPurchaseInvoice inv = invoiceMap.get(vr.getVendorPurchaseInvoiceId());
      if (inv == null) {
        continue;
      }
      Vendor vendor =
          inv.getVendorId() != null ? vendorMap.get(inv.getVendorId()) : null;
      boolean registered = vendor != null && StringUtils.hasText(vendor.getGstinUin());
      String supplierGstin =
          vendor != null && vendor.getGstinUin() != null ? vendor.getGstinUin().trim() : "";

      BigDecimal totalTaxable = BigDecimal.ZERO;
      BigDecimal totalCgst = BigDecimal.ZERO;
      BigDecimal totalSgst = BigDecimal.ZERO;
      if (vr.getItems() != null) {
        for (VendorPurchaseReturnItem it : vr.getItems()) {
          if (it.getTaxableValue() != null) {
            totalTaxable = totalTaxable.add(it.getTaxableValue());
          }
          if (it.getCentralTaxAmount() != null) {
            totalCgst = totalCgst.add(it.getCentralTaxAmount());
          }
          if (it.getStateUtTaxAmount() != null) {
            totalSgst = totalSgst.add(it.getStateUtTaxAmount());
          }
        }
      }
      BigDecimal noteValue =
          vr.getReturnAmount() != null
              ? vr.getReturnAmount()
              : totalTaxable.add(totalCgst).add(totalSgst);
      BigDecimal ratePct =
          totalTaxable.compareTo(BigDecimal.ZERO) > 0
              ? totalCgst.add(totalSgst).multiply(BigDecimal.valueOf(100)).divide(totalTaxable, 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;

      LocalDate noteDate =
          vr.getCreatedAt() != null
              ? LocalDateTime.ofInstant(vr.getCreatedAt(), ZoneId.systemDefault()).toLocalDate()
              : LocalDate.now();
      LocalDate origInvDate = null;
      if (inv.getInvoiceDate() != null) {
        origInvDate = LocalDateTime.ofInstant(inv.getInvoiceDate(), ZoneId.systemDefault()).toLocalDate();
      } else if (inv.getCreatedAt() != null) {
        origInvDate = LocalDateTime.ofInstant(inv.getCreatedAt(), ZoneId.systemDefault()).toLocalDate();
      }
      if (origInvDate == null) {
        origInvDate = LocalDate.now();
      }
      String noteNumber =
          StringUtils.hasText(vr.getSupplierCreditNoteNo())
              ? vr.getSupplierCreditNoteNo()
              : ("VCN-" + vr.getId());
      String invoiceNo =
          inv.getInvoiceNo() != null && !inv.getInvoiceNo().isBlank() ? inv.getInvoiceNo() : inv.getId();
      String reason =
          StringUtils.hasText(vr.getReason())
              ? vr.getReason().trim().length() > 120
                  ? vr.getReason().trim().substring(0, 120)
                  : vr.getReason().trim()
              : "Post purchase return";

      if (registered) {
        outCdnr.add(
            Gstr2CdnrLine.builder()
                .supplierGstin(supplierGstin)
                .noteNumber(noteNumber)
                .noteDate(noteDate)
                .invoiceNo(invoiceNo)
                .invoiceDate(origInvDate)
                .preGst("No")
                .documentType("Credit Note")
                .reasonForIssuing(reason)
                .supplyType("Intra State")
                .noteValue(noteValue)
                .rate(ratePct)
                .taxableValue(totalTaxable.setScale(2, RoundingMode.HALF_UP))
                .integratedTaxPaid(BigDecimal.ZERO)
                .centralTaxPaid(totalCgst.setScale(2, RoundingMode.HALF_UP))
                .stateUtTaxPaid(totalSgst.setScale(2, RoundingMode.HALF_UP))
                .cessPaid(BigDecimal.ZERO)
                .itcEligibility("Inputs")
                .availedItcIntegrated(BigDecimal.ZERO)
                .build());
      } else {
        outCdnur.add(
            Gstr2CdnurLine.builder()
                .noteNumber(noteNumber)
                .noteDate(noteDate)
                .invoiceNo(invoiceNo)
                .invoiceDate(origInvDate)
                .preGst("No")
                .documentType("Credit Note")
                .reasonForIssuing(reason)
                .supplyType("Intra State")
                .invoiceType("Purchases from unregistered supplier")
                .noteValue(noteValue)
                .rate(ratePct)
                .taxableValue(totalTaxable.setScale(2, RoundingMode.HALF_UP))
                .integratedTaxPaid(BigDecimal.ZERO)
                .centralTaxPaid(totalCgst.setScale(2, RoundingMode.HALF_UP))
                .stateUtTaxPaid(totalSgst.setScale(2, RoundingMode.HALF_UP))
                .cessPaid(BigDecimal.ZERO)
                .itcEligibility("Inputs")
                .availedItcIntegrated(BigDecimal.ZERO)
                .build());
      }
    }
  }
}
