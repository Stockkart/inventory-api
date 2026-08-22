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
import com.inventory.product.service.PackagingUnitCatalog;
import com.inventory.taxation.domain.model.*;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.utils.GstStateCode;
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

  /** UQC used when the base unit is absent or is not a GST quantity code. */
  private static final String FALLBACK_UQC = "OTH-OTHERS";

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
  @Autowired
  private HsnSacCatalog hsnSacCatalog;

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

    // The supplier's own state. It is the place of supply only for an unregistered
    // buyer, where no recipient registration exists to point anywhere else; for a
    // registered recipient the GSTIN decides, per line, below. Rendered as NN-Name
    // because the portal rejects a bare state name on upload.
    String sellerState = shop.getLocation() != null
        && StringUtils.hasText(shop.getLocation().getState())
        ? GstStateCode.format(shop.getLocation().getState())
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

      LocalDate invDate = purchase.getSoldAt() != null
          ? LocalDateTime.ofInstant(purchase.getSoldAt(), ZoneId.systemDefault()).toLocalDate()
          : null;
      if (invDate == null) invDate = LocalDate.now();

      String invNo = purchase.getInvoiceNo() != null ? purchase.getInvoiceNo() : ("INV-" + purchase.getId());

      // One row per rate the invoice carries. The invoice value is repeated on
      // each, which is how the portal's own export reads: it is a property of
      // the invoice, not of the rate, and splitting it would misstate both rows.
      for (RateShare share : splitByRate(purchase)) {
        GstInvoiceLine line = GstInvoiceLine.builder()
            .recipientGstin(recipientGstin)
            .receiverName(receiverName)
            .invoiceNo(invNo)
            .invoiceDate(invDate)
            .invoiceValue(invValue)
            .placeOfSupply(GstStateCode.placeOfSupply(recipientGstin, sellerState))
            .reverseCharge("N")
            .applicableTaxPct(share.rate)
            .invoiceType("Regular B2B") // Regular
            .ecommerceGstin("")
            .rate(parseRate(share.rate))
            .taxableValue(share.taxableValue)
            .cessAmount(BigDecimal.ZERO)
            .integratedTaxAmount(BigDecimal.ZERO)
            .centralTaxAmount(share.centralTax)
            .stateTaxAmount(share.stateTax)
            .build();

        if (b2b) {
          line.setSupplyType(SupplyType.B2B);
          b2bLines.add(line);
        } else if (invValue.compareTo(B2CL_THRESHOLD) >= 0) {
          line.setSupplyType(SupplyType.B2CL);
          b2clLines.add(line);
        } else {
          String key = "OE|" + line.getPlaceOfSupply() + "|" + share.rate;
          line.setB2csType("OE");
          line.setSupplyType(SupplyType.B2CS);
          b2csAggregate.merge(key, line, this::mergeB2csLine);
        }
      }

      // The HSN summary is per line already, so it is built once per invoice
      // however many rates the invoice carries.
      aggregateHsn(purchase, inventoryMap, b2b, b2b ? hsnB2bMap : hsnB2cMap);

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
      String rateStr = purchase != null ? dominantRate(purchase) : "0";
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
          .placeOfSupply(GstStateCode.placeOfSupply(recipientGstin, sellerState))
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
      // A document series runs from its lowest number to its highest. These were
      // taken as the first and last element of the list, which is the order the
      // purchases came back from Mongo -- so the declared range depended on
      // query order and could report a mid-series number as the start.
      List<String> serials = new ArrayList<>(invoiceSerialNos);
      Collections.sort(serials);
      docLines.add(GstDocumentSummaryLine.builder()
          .natureOfDocument("Invoices for outward supply")
          .srNoFrom(serials.get(0))
          .srNoTo(serials.get(serials.size() - 1))
          .totalNumber(serials.size())
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

  /** One rate's share of an invoice: what it was charged on and the tax it bore. */
  private static final class RateShare {
    private final String rate;
    private BigDecimal taxableValue = BigDecimal.ZERO;
    private BigDecimal centralTax = BigDecimal.ZERO;
    private BigDecimal stateTax = BigDecimal.ZERO;

    private RateShare(String rate) {
      this.rate = rate;
    }
  }

  /**
   * An invoice broken into one entry per tax rate it carries, in the order the
   * rates first appear on it.
   *
   * <p>GSTR-1 is reported per rate, not per invoice: an invoice carrying goods at
   * 5% and at 18% is two rows, each with its own taxable value and tax. Reporting
   * such an invoice under a single rate misstates the tax on everything charged
   * at the other -- in either direction, and on this shop's August it moved
   * ~6,700 rupees of 18% supplies into the 5% bucket, understating the liability.
   *
   * <p>An invoice with no lines keeps its purchase-level totals under rate 0, so
   * it is still reported rather than silently dropped.
   */
  private List<RateShare> splitByRate(Purchase purchase) {
    Map<String, RateShare> shares = new LinkedHashMap<>();
    if (purchase.getItems() != null) {
      for (PurchaseItem item : purchase.getItems()) {
        BigDecimal cgstVal = parseRate(item.getCgst());
        BigDecimal sgstVal = parseRate(item.getSgst());
        BigDecimal rate = cgstVal.add(sgstVal);
        String rateStr = rate.stripTrailingZeros().toPlainString();

        // The line total is tax inclusive, the same assumption the HSN summary
        // makes, so the taxable value is backed out of it rather than added to.
        BigDecimal gross = item.getTotalAmount() != null
            ? item.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal taxable = gross;
        if (rate.compareTo(BigDecimal.ZERO) > 0) {
          taxable = gross.multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
        }

        RateShare share = shares.computeIfAbsent(rateStr, RateShare::new);
        share.taxableValue = share.taxableValue.add(taxable);
        share.centralTax = share.centralTax.add(
            taxable.multiply(cgstVal).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        share.stateTax = share.stateTax.add(
            taxable.multiply(sgstVal).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
      }
    }
    if (shares.isEmpty()) {
      RateShare only = new RateShare("0");
      only.taxableValue = purchase.getRevenueBeforeTax() != null
          ? purchase.getRevenueBeforeTax() : BigDecimal.ZERO;
      only.centralTax = purchase.getCgstAmount() != null
          ? purchase.getCgstAmount() : BigDecimal.ZERO;
      only.stateTax = purchase.getSgstAmount() != null
          ? purchase.getSgstAmount() : BigDecimal.ZERO;
      shares.put("0", only);
    }
    return new ArrayList<>(shares.values());
  }

  /**
   * The single rate to report a credit note under: the one carrying most of the
   * original invoice's taxable value.
   *
   * <p>A note states one amount against the whole invoice, so splitting it
   * across rates would mean apportioning a figure the note does not break down.
   * Where an invoice carries more than one rate the note is reported under the
   * larger, which is the closer of the two available answers -- previously it
   * was reported under whichever rate happened to appear on the first line.
   */
  private String dominantRate(Purchase purchase) {
    return splitByRate(purchase).stream()
        .max(Comparator.comparing(share -> share.taxableValue))
        .map(share -> share.rate)
        .orElse("0");
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

  /**
   * The GST quantity code for a sale line, as {@code PCS-PIECES}.
   *
   * <p>A product's {@code baseUnit} is already a GST UQC — {@link PackagingUnitCatalog}
   * is the same master the portal uses, and it holds both the code and its label —
   * so the summary can report the real unit instead of a constant. The lot is
   * preferred because its base unit is hydrated from the product; the sale line's
   * own copy is the fallback for a lot that no longer exists.
   */
  static String resolveUqc(Inventory inv, PurchaseItem item) {
    String baseUnit = inv != null && StringUtils.hasText(inv.getBaseUnit())
        ? inv.getBaseUnit()
        : (item != null ? item.getBaseUnit() : null);
    if (!StringUtils.hasText(baseUnit)) {
      return FALLBACK_UQC;
    }
    return PackagingUnitCatalog.find(baseUnit)
        .map(def -> def.getUqc() + "-" + def.getLabel())
        .orElse(FALLBACK_UQC);
  }

  private void aggregateHsn(Purchase purchase, Map<String, Inventory> inventoryMap, boolean b2b,
                            Map<String, GstHsnLine> hsnMap) {
    if (purchase.getItems() == null) return;
    for (PurchaseItem item : purchase.getItems()) {
      Inventory inv = item.getInventoryId() != null ? inventoryMap.get(item.getInventoryId()) : null;
      // The line first, the lot second. A sale line can outlive the delivery it
      // came from -- stock is consumed, and a migrated sale points at a lot that
      // no longer exists -- and the lot was the only source, so every such line
      // fell through to "0", which is not a valid HSN and which the portal
      // rejects. Where the line states its own HSN, that is the authority.
      String hsn = StringUtils.hasText(item.getHsn())
          ? item.getHsn()
          : (inv != null && StringUtils.hasText(inv.getHsn()) ? inv.getHsn() : "0");
      String description = hsnSacCatalog.descriptionFor(hsn).orElseGet(() ->
          StringUtils.hasText(item.getName())
              ? item.getName()
              : (inv != null && inv.getDescription() != null ? inv.getDescription() : ""));
      String uqc = resolveUqc(inv, item);
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

      // A row per HSN and rate, which is how the summary is read and how the
      // portal's own export lays it out. UQC was in the key, which split one
      // HSN across a row per pack unit -- pieces, packs and phials of the same
      // goods -- where the return states one.
      //
      // The unit still has to be reported, and it is reported honestly: the
      // real unit where every line of the row shares one, and OTH-OTHERS where
      // they do not, since a row covering pieces and packs together is a row
      // whose quantity is in no single unit. That is what OTH-OTHERS means, and
      // it is what the shop's own filed returns carry for those rows.
      String key = hsn + "|" + rate;
      GstHsnLine existing = hsnMap.get(key);
      if (existing == null) {
        existing = GstHsnLine.builder()
            .hsn(hsn)
            .description(description)
            .uqc(uqc)
            .totalQuantity(qty)
            // Tax inclusive. The portal's own export reports this column above
            // the taxable value beside it -- 151,797 against 144,568 for one
            // HSN -- and reporting the taxable value twice makes the two
            // columns say the same thing, which is the one thing this column
            // cannot mean.
            .totalValue(totalAmount)
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
        if (!uqc.equals(existing.getUqc())) {
          existing.setUqc(FALLBACK_UQC);
        }
        existing.setTotalQuantity(existing.getTotalQuantity().add(qty));
        existing.setTotalValue(existing.getTotalValue().add(totalAmount));
        existing.setTaxableValue(existing.getTaxableValue().add(taxableVal));
        existing.setCentralTaxAmount(existing.getCentralTaxAmount().add(centralTaxAmount));
        existing.setStateUtTaxAmount(existing.getStateUtTaxAmount().add(stateUtTaxAmount));
      }
    }
  }
}
