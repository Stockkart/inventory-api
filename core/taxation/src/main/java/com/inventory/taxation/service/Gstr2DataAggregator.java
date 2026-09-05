package com.inventory.taxation.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
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
  private ProductRepository productRepository;
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
  @Autowired
  private HsnSacCatalog hsnSacCatalog;

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
    // Exclusive: the first instant of the next month is not part of this one.
    // It used to be that instant less a nanosecond, which a BSON date cannot
    // hold -- it truncates to the millisecond and the comparison then dropped
    // the last millisecond of the month as well.
    Instant rangeEnd = LocalDate.of(year, month, 1).plusMonths(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant();

    List<VendorPurchaseReturn> vendorReturns =
        vendorPurchaseReturnRepository.findByShopIdAndCreatedAtInPeriod(
                shopId, rangeStart, rangeEnd);

    // The supplier's own invoices for the period, where the shop has them. They
    // state what was bought; stock states what is left, and the two stop being
    // the same figure the moment anything is sold. A month whose invoices are
    // recorded is therefore reported from them and not from stock at all --
    // mixing the two would count the same goods under both.
    List<VendorPurchaseInvoice> purchaseInvoices =
        vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceDateInPeriod(
                shopId, rangeStart, rangeEnd)
            .stream()
            .filter(this::statesItsAmounts)
            .toList();

    List<Inventory> inventories = inventoryRepository.findByShopIdAndCreatedAtInPeriod(
            shopId, rangeStart, rangeEnd);
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
        String desc = hsnSacCatalog.descriptionFor(hsn).orElseGet(() ->
            inv.getDescription() != null ? inv.getDescription() : (inv.getName() != null ? inv.getName() : ""));
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
        .exempLines(buildExemptLines(inventories, pricingMap, vendorMap, placeOfSupply))
        .itcrLines(new ArrayList<>())
        .hsnLines(new ArrayList<>(hsnMap.values()))
        .build();
  }

  /**
   * Whether an invoice says enough to be reported.
   *
   * <p>What it must say is what the return is built from, and that is its lines:
   * every figure reported comes from a count times a cost, and {@link
   * #taxableByLine} already falls back to those when no subtotal was stated. The
   * header totals were gating a report they are not the source of, so an invoice
   * that stated its goods but never captured a total was dropped whole -- on one
   * shop that was three of August's twenty-six, and every purchase before April
   * 2026.
   */
  private boolean statesItsAmounts(VendorPurchaseInvoice invoice) {
    boolean stated = invoice.getLines() != null
        && invoice.getLines().stream().anyMatch(this::statesItsAmount);
    if (!stated) {
      log.warn("GSTR-2 leaves out purchase invoice {} ({}): no line states both a count "
          + "and a cost price", invoice.getInvoiceNo(), invoice.getId());
    }
    return stated;
  }

  /** A line states its amount when it says how many, and at what each cost. */
  private boolean statesItsAmount(VendorPurchaseInvoiceLine line) {
    return line.getCount() != null
        && line.getCount() > 0
        && line.getCostPrice() != null
        && line.getCostPrice().compareTo(BigDecimal.ZERO) > 0;
  }

  /**
   * What each line was worth before tax.
   *
   * <p>Count times cost carries only the two decimals the cost is stored to, so
   * the lines are scaled to the subtotal the invoice states and the last one
   * takes the remainder. The invoice then totals exactly what it says it does,
   * and only the split between its lines is arithmetic.
   */
  private List<BigDecimal> taxableByLine(VendorPurchaseInvoice invoice) {
    List<VendorPurchaseInvoiceLine> lines = invoice.getLines();
    List<BigDecimal> raw = new ArrayList<>(lines.size());
    BigDecimal sum = BigDecimal.ZERO;
    for (VendorPurchaseInvoiceLine line : lines) {
      BigDecimal value = line.getCostPrice() == null || line.getCount() == null
          ? BigDecimal.ZERO
          : line.getCostPrice().multiply(BigDecimal.valueOf(line.getCount()))
              .setScale(2, RoundingMode.HALF_UP);
      raw.add(value);
      sum = sum.add(value);
    }
    BigDecimal stated = invoice.getLineSubTotal();
    if (stated == null || sum.compareTo(BigDecimal.ZERO) <= 0) {
      return raw;
    }
    List<BigDecimal> scaled = new ArrayList<>(raw.size());
    BigDecimal running = BigDecimal.ZERO;
    for (BigDecimal value : raw) {
      BigDecimal share = value.multiply(stated).divide(sum, 2, RoundingMode.HALF_UP);
      scaled.add(share);
      running = running.add(share);
    }
    int last = scaled.size() - 1;
    scaled.set(last, scaled.get(last).add(stated.subtract(running)));
    return scaled;
  }

  private Map<String, Product> productsOf(List<Inventory> lots) {
    Set<String> ids = lots.stream().map(Inventory::getProductId)
        .filter(StringUtils::hasText).collect(Collectors.toSet());
    return ids.isEmpty() ? Map.of()
        : productRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Product::getId, product -> product));
  }

  private Map<String, Pricing> pricingOf(List<Inventory> lots) {
    Set<String> ids = lots.stream().map(Inventory::getPricingId)
        .filter(StringUtils::hasText).collect(Collectors.toSet());
    return ids.isEmpty() ? Map.of()
        : pricingRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Pricing::getId, pricing -> pricing));
  }

  /**
   * The state the shop supplies from, as a two-digit code.
   *
   * <p>Its GSTIN carries the code it registered under, which is the authority on
   * the question. A shop below the registration threshold has none, and is then
   * placed by the state on its address.
   */
  private String shopState(Shop shop) {
    String fromGstin = GstStateCode.codeFromGstin(shop.getGstinNo());
    if (StringUtils.hasText(fromGstin)) {
      return fromGstin;
    }
    return shop.getLocation() == null ? ""
        : GstStateCode.codeFromName(shop.getLocation().getState());
  }

  /**
   * The state a supplier supplies from, as a two-digit code.
   *
   * <p>A registered supplier is placed by their GSTIN. An unregistered one has no
   * GSTIN to read -- which is the whole reason they are reported on b2bur rather
   * than b2b -- so they are placed by the state named on their address. Reading
   * the state from the GSTIN alone left every b2bur line saying "Intra State",
   * because the only suppliers that sheet carries are the ones with no GSTIN.
   *
   * <p>Empty when neither says: an unplaceable supplier is treated as local,
   * which is what the far more common case actually is.
   */
  private String supplierState(Vendor vendor, String supplierGstin) {
    String fromGstin = GstStateCode.codeFromGstin(supplierGstin);
    if (StringUtils.hasText(fromGstin)) {
      return fromGstin;
    }
    return vendor == null ? "" : GstStateCode.codeFromAddress(vendor.getAddress());
  }

  /** The tax the goods on this line attract, read from what they were priced at. */
  private BigDecimal rateOf(Pricing pricing) {
    return pricing == null ? BigDecimal.ZERO
        : parseRate(pricing.getSgst()).add(parseRate(pricing.getCgst()));
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

    // A line knows its lot and nothing else about the goods. The lot names the
    // product, which carries the HSN, and the pricing, which carries the tax.
    Set<String> lotIds = invoices.stream()
        .flatMap(invoice -> invoice.getLines().stream())
        .map(VendorPurchaseInvoiceLine::getInventoryId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    List<Inventory> purchasedLots = lotIds.isEmpty() ? List.of()
        : inventoryRepository.findAllById(lotIds);
    Map<String, Inventory> lotMap = purchasedLots.stream()
        .collect(Collectors.toMap(Inventory::getId, lot -> lot));

    Map<String, Product> productMap = productsOf(purchasedLots);
    Map<String, Pricing> pricingMap = pricingOf(purchasedLots);

    // Inward supply from another state is taxed as IGST rather than split in two,
    // so both ends have to be placed. The shop is placed by its own GSTIN, and by
    // its address where it has not registered one.
    String shopState = shopState(shop);

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
      String supplierState = supplierState(vendor, supplierGstin);
      boolean interstate = StringUtils.hasText(shopState)
          && StringUtils.hasText(supplierState)
          && !supplierState.equals(shopState);

      List<BigDecimal> taxableByLine = taxableByLine(invoice);
      Map<String, BigDecimal[]> byRate = new LinkedHashMap<>();
      for (int i = 0; i < invoice.getLines().size(); i++) {
        VendorPurchaseInvoiceLine line = invoice.getLines().get(i);
        Inventory lot = lotMap.get(line.getInventoryId());
        Product product = lot == null ? null : productMap.get(lot.getProductId());
        Pricing pricing = lot == null ? null : pricingMap.get(lot.getPricingId());
        BigDecimal rate = rateOf(pricing);
        BigDecimal taxable = taxableByLine.get(i);
        // Halving the tax would hand the odd paisa to one side; an intra-state
        // purchase is taxed at half the rate twice, and the two are equal.
        BigDecimal half = rate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal integrated = interstate
            ? taxable.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal central = interstate ? BigDecimal.ZERO
            : taxable.multiply(half).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal state = central;
        BigDecimal tax = interstate ? integrated : central.add(state);

        BigDecimal[] bucket = byRate.computeIfAbsent(
            rate.stripTrailingZeros().toPlainString(),
            key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO});
        bucket[0] = bucket[0].add(taxable);
        bucket[1] = bucket[1].add(central);
        bucket[2] = bucket[2].add(state);
        bucket[3] = bucket[3].add(integrated);

        String hsn = product != null && StringUtils.hasText(product.getHsn())
            ? product.getHsn() : "0";
        BigDecimal quantity = BigDecimal.valueOf(
            line.getCount() != null ? line.getCount() : 0);
        BigDecimal gross = taxable.add(tax);
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
              .integratedTaxAmount(integrated)
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
          row.setIntegratedTaxAmount(row.getIntegratedTaxAmount().add(integrated));
        }
      }

      // An invoice is worth what it says it is worth, where it says so. The ones
      // that never captured a header are worth what their own rows come to, so
      // the value stamped on each row is the sum of every row's taxable value
      // and tax -- which is what the header would have stated.
      BigDecimal invoiceValue = invoice.getInvoiceTotal();
      if (invoiceValue == null) {
        invoiceValue = BigDecimal.ZERO;
        for (BigDecimal[] bucket : byRate.values()) {
          invoiceValue = invoiceValue
              .add(bucket[0]).add(bucket[1]).add(bucket[2]).add(bucket[3]);
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
              .integratedTaxPaid(bucket[3])
              .centralTaxPaid(bucket[1])
              .stateUtTaxPaid(bucket[2])
              .cessAmount(BigDecimal.ZERO)
              .itcEligibility("Inputs")
              .availedItcIntegrated(bucket[3])
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
              .supplyType(interstate ? "Inter State" : "Intra State")
              .rate(parseRate(entry.getKey()))
              .taxableValue(bucket[0])
              .integratedTaxPaid(bucket[3])
              .centralTaxPaid(bucket[1])
              .stateUtTaxPaid(bucket[2])
              .cessAmount(BigDecimal.ZERO)
              .itcEligibility("Inputs")
              .availedItcIntegrated(bucket[3])
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

  /** The two rows with nothing in them, for a period that received no stock. */
  private List<Gstr2ExempLine> buildDefaultExempLines() {
    return buildExemptLines(List.of(), Map.of(), Map.of(), "");
  }

  /**
   * Nil-rated / exempt / non-GST inward supplies, split inter- and intra-state.
   *
   * <p>These two rows were previously hardcoded to zero regardless of what the
   * shop actually received, so a period containing zero-rated purchases reported
   * none. They are now summed from the stock received in the period, at cost,
   * for lots whose pricing carries no GST.
   *
   * <p>Everything lands in the nil-rated column: GST separates nil-rated from
   * exempt from non-GST, but the platform records only that tax is zero and has
   * no field saying which of the three applies. The composition column stays
   * zero for the same reason -- nothing marks a vendor as a composition dealer.
   */
  private List<Gstr2ExempLine> buildExemptLines(
      List<Inventory> inventories, Map<String, Pricing> pricingMap,
      Map<String, Vendor> vendorMap, String sellerState) {
    String sellerCode = GstStateCode.codeFromName(stripStateCode(sellerState));
    BigDecimal inter = BigDecimal.ZERO;
    BigDecimal intra = BigDecimal.ZERO;

    for (Inventory inv : inventories) {
      Pricing pricing = inv.getPricingId() != null ? pricingMap.get(inv.getPricingId()) : null;
      BigDecimal rate = parseRate(pricing != null ? pricing.getSgst() : null)
          .add(parseRate(pricing != null ? pricing.getCgst() : null));
      if (rate.compareTo(BigDecimal.ZERO) > 0) {
        continue;
      }
      BigDecimal cost = pricing != null && pricing.getCostPrice() != null
          ? pricing.getCostPrice() : BigDecimal.ZERO;
      int count = inv.getReceivedBaseCount() != null ? inv.getReceivedBaseCount() : 0;
      BigDecimal value = cost.multiply(BigDecimal.valueOf(count));

      Vendor vendor = inv.getVendorId() != null ? vendorMap.get(inv.getVendorId()) : null;
      String supplierCode = vendor != null
          ? GstStateCode.codeFromGstin(vendor.getGstinUin()) : "";
      boolean interState = StringUtils.hasText(supplierCode)
          && StringUtils.hasText(sellerCode)
          && !supplierCode.equals(sellerCode);

      if (interState) {
        inter = inter.add(value);
      } else {
        intra = intra.add(value);
      }
    }

    return List.of(exemptLine("Inter-State supplies", inter),
        exemptLine("Intra-State supplies", intra));
  }

  private Gstr2ExempLine exemptLine(String description, BigDecimal nilRated) {
    return Gstr2ExempLine.builder()
        .description(description)
        .compositionTaxablePerson(BigDecimal.ZERO)
        .nilRatedSupplies(nilRated)
        .exemptedOtherThanNilOrNonGst(BigDecimal.ZERO)
        .nonGstSupplies(BigDecimal.ZERO)
        .build();
  }

  /** "10-Bihar" -> "Bihar"; a bare name is returned unchanged. */
  private static String stripStateCode(String placeOfSupply) {
    if (!StringUtils.hasText(placeOfSupply)) {
      return "";
    }
    int dash = placeOfSupply.indexOf('-');
    return dash == 2 ? placeOfSupply.substring(dash + 1) : placeOfSupply;
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
