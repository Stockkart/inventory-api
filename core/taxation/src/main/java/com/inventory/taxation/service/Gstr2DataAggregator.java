package com.inventory.taxation.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.taxation.domain.gstr2.*;
import com.inventory.taxation.domain.model.GstHsnLine;
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

    List<Inventory> inventories = inventoryRepository.findByShopIdAndCreatedAtBetween(shopId, rangeStart, rangeEnd);
    inventories = inventories.stream().filter(inv -> inv.getVendorId() != null).toList();

    if (inventories.isEmpty()) {
      return buildEmptyContext(shopId, shop, period, year, month);
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

    String placeOfSupply = shop.getLocation() != null && StringUtils.hasText(shop.getLocation().getState())
        ? shop.getLocation().getState()
        : "";

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

      String invoiceNo = first.getLotId() != null ? first.getLotId() : ("LOT-" + first.getId());
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
        .cdnrLines(new ArrayList<>())
        .cdnurLines(new ArrayList<>())
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

  private BigDecimal parseRate(String rateStr) {
    if (!StringUtils.hasText(rateStr)) return BigDecimal.ZERO;
    try {
      return new BigDecimal(rateStr.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
