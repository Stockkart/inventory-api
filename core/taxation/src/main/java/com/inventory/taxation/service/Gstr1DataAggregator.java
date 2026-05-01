package com.inventory.taxation.service;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.taxation.domain.model.*;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates purchase and refund data for a period into GSTR-1 report context.
 */
@Service
@Slf4j
public class Gstr1DataAggregator {

  private static final BigDecimal B2CL_THRESHOLD = new BigDecimal("250000");
  private static final int GSTIN_LENGTH = 15;

  @Autowired
  private PurchaseRepository purchaseRepository;
  @Autowired
  private RefundRepository refundRepository;
  @Autowired
  private ShopRepository shopRepository;
  @Autowired
  private CustomerRepository customerRepository;
  @Autowired
  private InventoryRepository inventoryRepository;

  public Gstr1ReportContext buildContext(String shopId, String period) {
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
    Instant rangeEnd = LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1);

    // Include completed purchases with soldAt in period, or with soldAt null but updatedAt in period (legacy/completion date)
    List<Purchase> purchases = purchaseRepository.findCompletedPurchasesInPeriod(
        shopId, PurchaseStatus.COMPLETED, rangeStart, rangeEnd);
    purchases = purchases.stream()
        .filter(this::isRegularBillingMode)
        .toList();

    List<Refund> refunds = refundRepository.findByShopIdAndCreatedAtBetween(shopId, rangeStart, rangeEnd);

    Set<String> customerIds = purchases.stream()
        .map(Purchase::getCustomerId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    Map<String, Customer> customerMap = customerIds.isEmpty() ? Map.of()
        : customerRepository.findAllById(customerIds).stream().collect(Collectors.toMap(Customer::getId, c -> c));

    Set<String> inventoryIds = new HashSet<>();
    for (Purchase p : purchases) {
      if (p.getItems() != null) {
        for (PurchaseItem item : p.getItems()) {
          if (StringUtils.hasText(item.getInventoryId())) inventoryIds.add(item.getInventoryId());
        }
      }
    }
    Map<String, Inventory> inventoryMap = inventoryIds.isEmpty() ? Map.of()
        : inventoryRepository.findByIdIn(new ArrayList<>(inventoryIds)).stream().collect(Collectors.toMap(Inventory::getId, inv -> inv));

    String placeOfSupply = shop.getLocation() != null && StringUtils.hasText(shop.getLocation().getState())
        ? shop.getLocation().getState()
        : "";

    Gstr1ReportContext.Gstr1ReportContextBuilder ctx = Gstr1ReportContext.builder()
        .shopId(shopId)
        .shopGstin(shop.getGstinNo() != null ? shop.getGstinNo() : "")
        .period(period)
        .year(year)
        .month(month);

    List<GstInvoiceLine> b2bLines = new ArrayList<>();
    List<GstInvoiceLine> b2clLines = new ArrayList<>();
    Map<String, GstInvoiceLine> b2csAggregate = new LinkedHashMap<>(); // key: type|place|rate

    List<GstRefundLine> cdnrLines = new ArrayList<>();
    List<GstRefundLine> cdnurLines = new ArrayList<>();

    List<GstHsnLine> hsnB2bAccum = new ArrayList<>();
    Map<String, GstHsnLine> hsnB2bMap = new HashMap<>();
    Map<String, GstHsnLine> hsnB2cMap = new HashMap<>();

    int invoiceSerial = 1;
    List<String> invoiceSerialNos = new ArrayList<>();

    for (Purchase purchase : purchases) {
      Customer customer = purchase.getCustomerId() != null ? customerMap.get(purchase.getCustomerId()) : null;
      boolean b2b = isRegisteredRecipient(customer);
      String receiverName = customer != null ? customer.getName() : purchase.getCustomerName();
      if (receiverName == null) receiverName = "";
      String recipientGstin = customer != null && customer.getGstin() != null ? customer.getGstin() : "";

      BigDecimal invValue = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
      BigDecimal taxableVal = purchase.getRevenueBeforeTax() != null ? purchase.getRevenueBeforeTax() : BigDecimal.ZERO;
      String rateStr = getApplicableRateFromPurchase(purchase);
      BigDecimal rate = parseRate(rateStr);

      LocalDate invDate = purchase.getSoldAt() != null
          ? LocalDateTime.ofInstant(purchase.getSoldAt(), ZoneId.systemDefault()).toLocalDate()
          : null;
      if (invDate == null) invDate = LocalDate.now();

      String invNo = purchase.getInvoiceNo() != null ? purchase.getInvoiceNo() : ("INV-" + purchase.getId());

      GstInvoiceLine line = GstInvoiceLine.builder()
          .recipientGstin(recipientGstin)
          .receiverName(receiverName)
          .invoiceNo(invNo)
          .invoiceDate(invDate)
          .invoiceValue(invValue)
          .placeOfSupply(placeOfSupply)
          .reverseCharge("N")
          .applicableTaxPct(rateStr)
          .invoiceType("Regular B2B") // Regular
          .ecommerceGstin("")
          .rate(rate)
          .taxableValue(taxableVal)
          .cessAmount(BigDecimal.ZERO)
          .integratedTaxAmount(BigDecimal.ZERO)
          .centralTaxAmount(purchase.getCgstAmount() != null ? purchase.getCgstAmount() : BigDecimal.ZERO)
          .stateTaxAmount(purchase.getSgstAmount() != null ? purchase.getSgstAmount() : BigDecimal.ZERO)
          .build();

      if (b2b) {
        line.setSupplyType(SupplyType.B2B);
        b2bLines.add(line);
        aggregateHsn(purchase, inventoryMap, true, hsnB2bMap);
      } else {
        if (invValue.compareTo(B2CL_THRESHOLD) >= 0) {
          line.setSupplyType(SupplyType.B2CL);
          b2clLines.add(line);
        } else {
          String key = "OE|" + placeOfSupply + "|" + rateStr;
          line.setB2csType("OE");
          line.setSupplyType(SupplyType.B2CS);
          b2csAggregate.merge(key, line, this::mergeB2csLine);
        }
        aggregateHsn(purchase, inventoryMap, false, hsnB2cMap);
      }

      invoiceSerialNos.add(invNo);
    }

    ctx.b2bLines(b2bLines)
        .b2clLines(b2clLines)
        .b2csLines(new ArrayList<>(b2csAggregate.values()))
        .hsnB2bLines(new ArrayList<>(hsnB2bMap.values()))
        .hsnB2cLines(new ArrayList<>(hsnB2cMap.values()));

    for (Refund refund : refunds) {
      Purchase purchase = purchaseRepository.findById(refund.getPurchaseId()).orElse(null);
      if (purchase != null && !isRegularBillingMode(purchase)) {
        continue;
      }
      Customer customer = purchase != null && purchase.getCustomerId() != null
          ? customerMap.get(purchase.getCustomerId()) : null;
      boolean registered = isRegisteredRecipient(customer);
      String receiverName = customer != null ? customer.getName() : (purchase != null ? purchase.getCustomerName() : "");
      if (receiverName == null) receiverName = "";
      String recipientGstin = customer != null && customer.getGstin() != null ? customer.getGstin() : "";

      BigDecimal noteValue = refund.getRefundAmount() != null ? refund.getRefundAmount() : BigDecimal.ZERO;
      BigDecimal taxableVal = noteValue;
      if (purchase != null && purchase.getGrandTotal() != null && purchase.getGrandTotal().compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal ratio = noteValue.divide(purchase.getGrandTotal(), 4, RoundingMode.HALF_UP);
        if (purchase.getRevenueBeforeTax() != null) {
          taxableVal = purchase.getRevenueBeforeTax().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        }
      }
      String rateStr = purchase != null ? getApplicableRateFromPurchase(purchase) : "0";
      BigDecimal rate = parseRate(rateStr);

      LocalDate noteDate = refund.getCreatedAt() != null
          ? LocalDateTime.ofInstant(refund.getCreatedAt(), ZoneId.systemDefault()).toLocalDate()
          : LocalDate.now();
      String noteNumber = StringUtils.hasText(refund.getCreditNoteNo())
          ? refund.getCreditNoteNo()
          : ("CN-" + refund.getId());

      GstRefundLine rLine = GstRefundLine.builder()
          .registered(registered)
          .recipientGstin(recipientGstin)
          .receiverName(receiverName)
          .noteNumber(noteNumber)
          .noteDate(noteDate)
          .noteType("C")
          .placeOfSupply(placeOfSupply)
          .reverseCharge("N")
          .noteSupplyType("R")
          .noteValue(noteValue)
          .applicableTaxPct(rateStr)
          .rate(rate)
          .taxableValue(taxableVal)
          .cessAmount(BigDecimal.ZERO)
          .urType(registered ? "" : "UR")
          .build();

      if (registered) cdnrLines.add(rLine);
      else cdnurLines.add(rLine);
    }

    ctx.cdnrLines(cdnrLines).cdnurLines(cdnurLines);

    List<GstDocumentSummaryLine> docLines = new ArrayList<>();
    if (!invoiceSerialNos.isEmpty()) {
      docLines.add(GstDocumentSummaryLine.builder()
          .natureOfDocument("Invoices for outward supply")
          .srNoFrom(invoiceSerialNos.get(0))
          .srNoTo(invoiceSerialNos.get(invoiceSerialNos.size() - 1))
          .totalNumber(invoiceSerialNos.size())
          .cancelled(0)
          .build());
    }
    if (!cdnrLines.isEmpty()) {
      docLines.add(GstDocumentSummaryLine.builder()
          .natureOfDocument("Credit/Debit Notes (Registered)")
          .srNoFrom(cdnrLines.get(0).getNoteNumber())
          .srNoTo(cdnrLines.get(cdnrLines.size() - 1).getNoteNumber())
          .totalNumber(cdnrLines.size())
          .cancelled(0)
          .build());
    }
    if (!cdnurLines.isEmpty()) {
      docLines.add(GstDocumentSummaryLine.builder()
          .natureOfDocument("Credit/Debit Notes (Unregistered)")
          .srNoFrom(cdnurLines.get(0).getNoteNumber())
          .srNoTo(cdnurLines.get(cdnurLines.size() - 1).getNoteNumber())
          .totalNumber(cdnurLines.size())
          .cancelled(0)
          .build());
    }
    ctx.docLines(docLines);

    ctx.atLines(new ArrayList<>());
    ctx.atadjLines(new ArrayList<>());
    ctx.exempLines(new ArrayList<>());
    ctx.expLines(new ArrayList<>());

    return ctx.build();
  }

  private boolean isRegisteredRecipient(Customer customer) {
    if (customer == null) return false;
    String g = customer.getGstin();
    return StringUtils.hasText(g);
  }

  private boolean isRegularBillingMode(Purchase purchase) {
    BillingMode mode = purchase != null && purchase.getBillingMode() != null
        ? purchase.getBillingMode()
        : BillingMode.REGULAR;
    return mode == BillingMode.REGULAR;
  }

  private String getApplicableRateFromPurchase(Purchase purchase) {
    if (purchase.getItems() == null || purchase.getItems().isEmpty()) return "0";
    PurchaseItem first = purchase.getItems().get(0);
    BigDecimal sgstVal = parseRate(first.getSgst());
    BigDecimal cgstVal = parseRate(first.getCgst());
    return sgstVal.add(cgstVal).stripTrailingZeros().toPlainString();
  }

  private BigDecimal parseRate(String rateStr) {
    if (!StringUtils.hasText(rateStr)) return BigDecimal.ZERO;
    try {
      return new BigDecimal(rateStr.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private GstInvoiceLine mergeB2csLine(GstInvoiceLine a, GstInvoiceLine b) {
    return GstInvoiceLine.builder()
        .supplyType(SupplyType.B2CS)
        .b2csType(a.getB2csType())
        .placeOfSupply(a.getPlaceOfSupply())
        .applicableTaxPct(a.getApplicableTaxPct())
        .rate(a.getRate())
        .taxableValue(a.getTaxableValue().add(b.getTaxableValue() != null ? b.getTaxableValue() : BigDecimal.ZERO))
        .invoiceValue(a.getInvoiceValue().add(b.getInvoiceValue() != null ? b.getInvoiceValue() : BigDecimal.ZERO))
        .centralTaxAmount(a.getCentralTaxAmount().add(b.getCentralTaxAmount() != null ? b.getCentralTaxAmount() : BigDecimal.ZERO))
        .stateTaxAmount(a.getStateTaxAmount().add(b.getStateTaxAmount() != null ? b.getStateTaxAmount() : BigDecimal.ZERO))
        .cessAmount(BigDecimal.ZERO)
        .build();
  }

  private void aggregateHsn(Purchase purchase, Map<String, Inventory> inventoryMap, boolean b2b,
                            Map<String, GstHsnLine> hsnMap) {
    if (purchase.getItems() == null) return;
    for (PurchaseItem item : purchase.getItems()) {
      Inventory inv = item.getInventoryId() != null ? inventoryMap.get(item.getInventoryId()) : null;
      String hsn = inv != null && StringUtils.hasText(inv.getHsn()) ? inv.getHsn() : "0";
      String description = inv != null ? inv.getDescription() : "";
      BigDecimal sgstVal = parseRate(item.getSgst());
      BigDecimal cgstVal = parseRate(item.getCgst());
      BigDecimal rate = sgstVal.add(cgstVal);
      BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
      BigDecimal totalAmount = item.getTotalAmount() != null ? item.getTotalAmount() : BigDecimal.ZERO;
      BigDecimal taxableVal = totalAmount;
      if (rate.compareTo(BigDecimal.ZERO) > 0) {
        // rate is already sgst+cgst (e.g. 18 for 9%+9%)
        taxableVal = totalAmount.multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
      }
      BigDecimal centralTaxAmount = taxableVal.multiply(cgstVal)
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      BigDecimal stateUtTaxAmount = taxableVal.multiply(sgstVal)
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

      String key = hsn + "|" + rate;
      GstHsnLine existing = hsnMap.get(key);
      if (existing == null) {
        existing = GstHsnLine.builder()
            .hsn(hsn)
            .description(description)
            .uqc("OTH-OTHERS")
            .totalQuantity(qty)
            .totalValue(taxableVal)
            .rate(rate)
            .taxableValue(taxableVal)
            .integratedTaxAmount(BigDecimal.ZERO)
            .centralTaxAmount(centralTaxAmount)
            .stateUtTaxAmount(stateUtTaxAmount)
            .cessAmount(BigDecimal.ZERO)
            .b2b(b2b)
            .build();
        hsnMap.put(key, existing);
      } else {
        existing.setTotalQuantity(existing.getTotalQuantity().add(qty));
        existing.setTotalValue(existing.getTotalValue().add(taxableVal));
        existing.setTaxableValue(existing.getTaxableValue().add(taxableVal));
        existing.setCentralTaxAmount(existing.getCentralTaxAmount().add(centralTaxAmount));
        existing.setStateUtTaxAmount(existing.getStateUtTaxAmount().add(stateUtTaxAmount));
      }
    }
  }
}
