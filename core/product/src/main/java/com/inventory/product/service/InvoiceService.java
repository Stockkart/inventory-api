package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import com.inventory.documentservice.rest.dto.InvoiceTaxRateRow;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.DocumentTypes;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.service.estimate.EstimateInventoryPolicy;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.product.utils.AmountToWordsConverter;
import com.inventory.product.utils.CheckoutUtils;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for generating invoices from purchases.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class InvoiceService {

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private com.inventory.product.service.vertical.InventoryVerticalExtensionHandler
      inventoryVerticalExtensionHandler;

  @Autowired
  private CustomerService customerService;

  @Autowired
  private DocumentService documentService;

  @Autowired
  private InvoiceSettingsService invoiceSettingsService;

  @Autowired
  private EstimateInventoryPolicy estimateInventoryPolicy;

  @Autowired(required = false)
  private com.inventory.metrics.MetricsWrapper metrics;

  /**
   * Generate invoice PDF for a purchase.
   *
   * @param purchaseId the purchase ID
   * @param shopId the shop ID for validation
   * @param printerType optional printer type (NORMAL, DOT_MATRIX, or THERMAL_3INCH);
   *     when blank, uses the shop default from invoice settings
   * @return PDF as byte array
   */
  public byte[] generateInvoicePdf(String purchaseId, String shopId, String printerType) {
    log.info("Generating invoice PDF for purchase: {}, shop: {}", purchaseId, shopId);

    Purchase purchase = purchaseRepository.findById(purchaseId)
        .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id", purchaseId));

    if (!shopId.equals(purchase.getShopId())) {
      throw new ValidationException("Purchase does not belong to the specified shop");
    }

    if (DocumentTypes.isEstimate(purchase)) {
      estimateInventoryPolicy.assertPrintable(purchase);
    }

    Shop shop = shopRepository.findById(purchase.getShopId())
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", purchase.getShopId()));

    var settings = invoiceSettingsService.getOrDefaultForShop(shopId);
    GenerateInvoiceRequest request = buildGenerateInvoiceRequest(purchase, shop, settings);

    String resolvedPrinter =
        (printerType != null && !printerType.isBlank())
            ? printerType
            : settings.getDefaultPrinterType();
    request.setPrinterType(resolvedPrinter);

    byte[] pdf = documentService.generateInvoice(request);
    if (metrics != null) {
      metrics.record(ProductMetricsConstants.INVOICES_GENERATED, 1, "module", ProductMetricsConstants.MODULE);
    }
    return pdf;
  }

  /**
   * Build GenerateInvoiceRequest from Purchase, Shop, and shop invoice settings.
   * Visibility flags control template display; data is always populated when available.
   */
  private GenerateInvoiceRequest buildGenerateInvoiceRequest(
      Purchase purchase,
      Shop shop,
      com.inventory.product.domain.model.ShopInvoiceSettingsDocument settings) {
    GenerateInvoiceRequest request = new GenerateInvoiceRequest();
    BillingMode billingMode = purchase.getBillingMode() != null ? purchase.getBillingMode() : BillingMode.REGULAR;
    request.setBillingMode(billingMode.name());
    boolean isEstimateDoc =
        purchase.getDocumentType()
            == com.inventory.product.domain.model.enums.DocumentType.ESTIMATE;
    // Estimate chrome: Scan & Sell estimates OR BASIC bills — same template settings
    boolean estimateChrome = isEstimateDoc || billingMode == BillingMode.BASIC;
    request.setDocumentType(estimateChrome ? "ESTIMATE" : "SALE");

    var fields =
        estimateChrome
            ? invoiceSettingsService.fieldsForMode(settings, BillingMode.BASIC)
            : invoiceSettingsService.fieldsForMode(settings, billingMode);
    invoiceSettingsService.applyVisibility(request, fields);
    request.setFooterNote(settings.getFooterNote() != null ? settings.getFooterNote() : "");
    if (estimateChrome) {
      // Shared estimate template: tax only when the bill's products are REGULAR (GST)
      request.setShowTaxDetails(billingMode == BillingMode.REGULAR);
    }
    if (isEstimateDoc) {
      // Open quotes never show payment — BASIC completed sales still honor settings
      request.setShowPaymentMethod(false);
    }

    // Invoice / estimate number
    if (isEstimateDoc && StringUtils.hasText(purchase.getEstimateNo())) {
      request.setInvoiceNo(purchase.getEstimateNo());
    } else {
      request.setInvoiceNo(purchase.getInvoiceNo() != null ? purchase.getInvoiceNo() : "");
    }
    Instant dateSource =
        purchase.getSoldAt() != null
            ? purchase.getSoldAt()
            : (purchase.getUpdatedAt() != null ? purchase.getUpdatedAt() : purchase.getCreatedAt());
    if (dateSource != null) {
      LocalDateTime soldAt = LocalDateTime.ofInstant(dateSource, ZoneId.of("Asia/Kolkata"));
      request.setInvoiceDate(soldAt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
      request.setInvoiceTime(soldAt.format(DateTimeFormatter.ofPattern("hh:mm a")));
    }

    // Shop/Seller information (always populate; templates honor show* flags)
    request.setShopName(shop.getName() != null ? shop.getName() : "");
    if (shop.getLocation() != null) {
      List<String> addressParts = new ArrayList<>();
      if (shop.getLocation().getPrimaryAddress() != null) {
        addressParts.add(shop.getLocation().getPrimaryAddress());
      }
      if (shop.getLocation().getSecondaryAddress() != null) {
        addressParts.add(shop.getLocation().getSecondaryAddress());
      }
      if (shop.getLocation().getCity() != null) {
        addressParts.add(shop.getLocation().getCity());
      }
      if (shop.getLocation().getState() != null) {
        addressParts.add(shop.getLocation().getState());
      }
      if (shop.getLocation().getPin() != null) {
        addressParts.add(shop.getLocation().getPin());
      }
      request.setShopAddress(String.join(", ", addressParts));
      if (shop.getLocation().getState() != null && !shop.getLocation().getState().isEmpty()) {
        request.setPlaceOfSupply(shop.getLocation().getState());
      }
    }
    request.setShopDlNo(shop.getDlNo());
    request.setShopFssai(shop.getFssai());
    request.setShopGstin(shop.getGstinNo());
    request.setShopPhone(shop.getContactPhone());
    request.setShopEmail(shop.getContactEmail());
    request.setShopTagline(shop.getTagline());
    String shopPan = shop.getPanNo();
    if ((shopPan == null || shopPan.isEmpty())
        && shop.getGstinNo() != null
        && shop.getGstinNo().length() >= 12) {
      shopPan = shop.getGstinNo().substring(2, 12);
    }
    request.setShopPan(shopPan);

    // Customer/Buyer information
    if (purchase.getCustomerId() != null && !purchase.getCustomerId().isEmpty()) {
      Optional<Customer> customerOpt = customerService.getCustomerById(purchase.getCustomerId());
      if (customerOpt.isPresent()) {
        Customer customer = customerOpt.get();
        if (customer.isGeneralCustomer()) {
          String overlay = PurchaseCustomerRequests.sanitizedDisplayName(purchase.getCustomerName());
          if (overlay != null) {
            request.setCustomerName(overlay);
          }
        } else {
          request.setCustomerName(customer.getName());
          request.setCustomerAddress(customer.getAddress());
          request.setCustomerDlNo(customer.getDlNo());
          request.setCustomerGstin(customer.getGstin());
          request.setCustomerPan(customer.getPan());
          request.setCustomerPhone(customer.getPhone());
          request.setCustomerEmail(customer.getEmail());
        }
      }
    } else if (purchase.getCustomerName() != null && !purchase.getCustomerName().isEmpty()) {
      String overlay = PurchaseCustomerRequests.sanitizedDisplayName(purchase.getCustomerName());
      if (overlay != null) {
        request.setCustomerName(overlay);
      }
    }

    List<InvoiceItem> invoiceItems = new ArrayList<>();
    InvoiceFooter footer = new InvoiceFooter();
    if (purchase.getItems() != null) {
      for (PurchaseItem purchaseItem : purchase.getItems()) {
        InvoiceItem invoiceItem = new InvoiceItem();
        invoiceItem.setQuantity(purchaseItem.getQuantity());
        invoiceItem.setName(purchaseItem.getName());
        invoiceItem.setMaximumRetailPrice(purchaseItem.getMaximumRetailPrice());
        // A line sold at MRP prints its rate with the GST inside it taken out, as every other
        // line's rate already is: the RATE column is the taxable price, and the GST is stated
        // beneath the lines rather than left hidden in the rate.
        invoiceItem.setPriceToRetail(CheckoutUtils.isSellingAtMrp(purchaseItem)
            ? taxablePrice(purchaseItem.getPriceToRetail(), CheckoutUtils.combinedGstRate(purchaseItem))
            : purchaseItem.getPriceToRetail());
        footer.add(purchaseItem);
        invoiceItem.setDiscount(purchaseItem.getDiscount());
        invoiceItem.setSaleAdditionalDiscount(purchaseItem.getSaleAdditionalDiscount());
        invoiceItem.setTotalAmount(purchaseItem.getTotalAmount());
        invoiceItem.setCgst(purchaseItem.getCgst());
        invoiceItem.setSgst(purchaseItem.getSgst());
        invoiceItem.setGstPercent(sumTaxRates(purchaseItem.getCgst(), purchaseItem.getSgst()));
        invoiceItem.setInventoryId(purchaseItem.getInventoryId());
        invoiceItem.setSchemePayFor(purchaseItem.getSchemePayFor());
        invoiceItem.setSchemeFree(purchaseItem.getSchemeFree());
        // A percentage scheme on the line normalises pay-for/free away, so without this the
        // SCHEME column had nothing to print and fell through to the lot - which a line with no
        // inventoryId does not have either, leaving the column blank on a sale that had a scheme.
        if (purchaseItem.getSchemeType() == SchemeType.PERCENTAGE
            && purchaseItem.getSchemePercentage() != null
            && purchaseItem.getSchemePercentage().signum() > 0) {
          invoiceItem.setSchemePercentage(purchaseItem.getSchemePercentage());
        }
        // The line's own HSN, before the lot is consulted. Everything printed in
        // the tax columns used to come from the lot alone, so a line whose lot is
        // gone -- stock sold out, or a migrated sale that never pointed at one --
        // printed an empty HSN on a tax invoice. Where the line states its HSN,
        // that is what was charged and what belongs on the bill.
        invoiceItem.setHsn(purchaseItem.getHsn());
        invoiceItem.setBatchNo(purchaseItem.getBatchNo());
        invoiceItem.setCompanyName(purchaseItem.getCompanyName());
        invoiceItem.setExpiryDate(purchaseItem.getExpiryDate());

        if (purchaseItem.getInventoryId() != null) {
          Optional<Inventory> inventoryOpt = inventoryRepository.findById(purchaseItem.getInventoryId());
          if (inventoryOpt.isPresent()) {
            Inventory inventory = inventoryOpt.get();
            Map<String, Object> extensionFields =
                inventoryVerticalExtensionHandler.loadExtensionFields(
                    inventory.getShopId(), inventory.getId());
            if (!StringUtils.hasText(invoiceItem.getHsn())) {
              invoiceItem.setHsn(inventory.getHsn());
            }
            if (!StringUtils.hasText(invoiceItem.getCompanyName())) {
              invoiceItem.setCompanyName(inventory.getCompanyName());
            }
            if (!StringUtils.hasText(invoiceItem.getBatchNo())) {
              invoiceItem.setBatchNo(VerticalFieldsReader.batchNoFrom(extensionFields));
            }
            if (inventory.getSchemeType() == SchemeType.PERCENTAGE
                && inventory.getSchemePercentage() != null
                && inventory.getReceivedCount() != null
                && inventory.getSchemePercentage().signum() > 0) {
              BigDecimal pct = inventory.getSchemePercentage();
              int effectiveFree = pct.multiply(inventory.getReceivedCount())
                  .divide(BigDecimal.valueOf(100).add(pct), 0, RoundingMode.HALF_UP).intValue();
              invoiceItem.setScheme(effectiveFree);
            } else if (inventory.getSchemePayFor() != null && inventory.getSchemeFree() != null) {
              invoiceItem.setScheme(inventory.getSchemeFree());
            } else {
              invoiceItem.setScheme(inventory.getScheme());
            }
            if (!StringUtils.hasText(invoiceItem.getExpiryDate())
                && VerticalFieldsReader.expiryDateFrom(extensionFields) != null) {
              LocalDateTime expiryDateTime =
                  LocalDateTime.ofInstant(
                      VerticalFieldsReader.expiryDateFrom(extensionFields),
                      ZoneId.of("Asia/Kolkata"));
              invoiceItem.setExpiryDate(expiryDateTime.format(DateTimeFormatter.ofPattern("MM/yy")));
            }
          }
        }

        invoiceItems.add(invoiceItem);
      }
    }
    request.setItems(invoiceItems);

    BigDecimal totalMRPAmount = BigDecimal.ZERO;
    for (InvoiceItem item : invoiceItems) {
      if (item.getMaximumRetailPrice() != null && item.getQuantity() != null) {
        totalMRPAmount = totalMRPAmount.add(item.getMaximumRetailPrice().multiply(item.getQuantity()));
      }
    }
    request.setTotalMRPAmount(totalMRPAmount);

    request.setSubTotal(purchase.getSubTotal() != null ? purchase.getSubTotal() : BigDecimal.ZERO);
    request.setDiscountTotal(purchase.getDiscountTotal() != null ? purchase.getDiscountTotal() : BigDecimal.ZERO);
    request.setSaleAdditionalDiscountTotal(purchase.getSaleAdditionalDiscountTotal() != null ? purchase.getSaleAdditionalDiscountTotal() : BigDecimal.ZERO);
    request.setSgstAmount(purchase.getSgstAmount() != null ? purchase.getSgstAmount() : BigDecimal.ZERO);
    request.setCgstAmount(purchase.getCgstAmount() != null ? purchase.getCgstAmount() : BigDecimal.ZERO);
    request.setTaxTotal(purchase.getTaxTotal() != null ? purchase.getTaxTotal() : BigDecimal.ZERO);
    if (footer.isUsable()) {
      footer.split();
      // The footer is worked from the lines, the way GSTR-1 reads the same sale, rather than
      // copied from the header. Bills saved before tax was taken out of MRP carry a header that
      // states no tax on those lines and a subtotal that still holds it; the lines do not.
      request.setSubTotal(footer.gross);
      request.setSaleAdditionalDiscountTotal(footer.gross.subtract(footer.taxable));
      request.setTaxRateRows(new ArrayList<>(footer.rows.values()));
      request.setSgstAmount(footer.sgst);
      request.setCgstAmount(footer.cgst);
      request.setTaxTotal(footer.sgst.add(footer.cgst));
    }

    if (!invoiceItems.isEmpty()) {
      InvoiceItem firstItem = invoiceItems.get(0);
      if (firstItem.getSgst() != null && !firstItem.getSgst().trim().isEmpty()) {
        try {
          request.setSgstPercent(new BigDecimal(firstItem.getSgst().trim()));
        } catch (NumberFormatException e) {
          request.setSgstPercent(BigDecimal.valueOf(2.5));
        }
      } else {
        request.setSgstPercent(BigDecimal.valueOf(2.5));
      }
      if (firstItem.getCgst() != null && !firstItem.getCgst().trim().isEmpty()) {
        try {
          request.setCgstPercent(new BigDecimal(firstItem.getCgst().trim()));
        } catch (NumberFormatException e) {
          request.setCgstPercent(BigDecimal.valueOf(2.5));
        }
      } else {
        request.setCgstPercent(BigDecimal.valueOf(2.5));
      }
    } else {
      request.setSgstPercent(BigDecimal.valueOf(2.5));
      request.setCgstPercent(BigDecimal.valueOf(2.5));
    }

    BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
    // The additional discount is what comes off the subtotal. The trade discount is the gap
    // between MRP and rate, already inside the subtotal, so subtracting it misstated round-off.
    BigDecimal calculatedTotal = request.getSubTotal()
        .subtract(request.getSaleAdditionalDiscountTotal())
        .add(request.getTaxTotal());
    request.setRoundOff(grandTotal.subtract(calculatedTotal));
    request.setGrandTotal(grandTotal);
    request.setTotalAmountSaved(totalMRPAmount.subtract(grandTotal));

    request.setPaymentMethod(purchase.getPaymentMethod());
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(grandTotal));
    request.setSoldAt(purchase.getSoldAt());

    return request;
  }

  private static BigDecimal taxablePrice(BigDecimal price, BigDecimal gstRate) {
    if (price == null || gstRate.signum() <= 0) {
      return price;
    }
    return price.multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(100).add(gstRate), 2, RoundingMode.HALF_UP);
  }

  /**
   * The invoice footer, added up line by line: the value before the additional discount, the
   * taxable value after it, and the CGST and SGST at each rate.
   *
   * <p>A line's amount includes its GST, so its taxable value is the amount with that GST taken
   * out at the line's own rate, and its tax is the rest. That holds for a line priced before tax,
   * where the GST was added to reach the amount, and for one sold at MRP, where it was already
   * inside it.
   */
  static final class InvoiceFooter {
    final Map<String, InvoiceTaxRateRow> rows = new LinkedHashMap<>();
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal taxable = BigDecimal.ZERO;
    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    private boolean everyLineHasAmount = true;

    void add(PurchaseItem item) {
      if (item.getTotalAmount() == null) {
        everyLineHasAmount = false;
        return;
      }
      BigDecimal amount = item.getTotalAmount();
      BigDecimal cgstPct = parseTaxRate(item.getCgst());
      BigDecimal sgstPct = parseTaxRate(item.getSgst());
      BigDecimal rate = cgstPct.add(sgstPct);
      BigDecimal lineTaxable = rate.signum() > 0
          ? amount.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP)
          : amount;
      // At the price as printed in the RATE column, so Total Amount is that column times quantity.
      gross = gross.add(CheckoutUtils.getTaxablePricePerUnit(item).setScale(2, RoundingMode.HALF_UP)
          .multiply(CheckoutUtils.getBillableQuantityAsDecimal(item))
          .setScale(2, RoundingMode.HALF_UP));
      taxable = taxable.add(lineTaxable);
      if (rate.signum() <= 0) {
        return;
      }
      BigDecimal lineTax = amount.subtract(lineTaxable);
      // Keyed on the rate's value, not its spelling: "9" and "9.00" are one rate.
      String key = cgstPct.stripTrailingZeros().toPlainString() + "|" + sgstPct.stripTrailingZeros().toPlainString();
      InvoiceTaxRateRow row = rows.computeIfAbsent(key, k -> new InvoiceTaxRateRow(
          cgstPct, sgstPct, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      row.setTaxableValue(row.getTaxableValue().add(lineTaxable));
      // The row's whole tax is held in CGST until the row is split, once, below.
      row.setCgstAmount(row.getCgstAmount().add(lineTax));
    }

    /**
     * Splits each row's tax into CGST and SGST. Done once per row rather than per line: rounding
     * each line's half up and giving SGST the remainder drifts the two apart by a paisa a line.
     */
    void split() {
      cgst = BigDecimal.ZERO;
      sgst = BigDecimal.ZERO;
      for (InvoiceTaxRateRow row : rows.values()) {
        BigDecimal tax = row.getCgstAmount().add(row.getSgstAmount());
        BigDecimal rate = row.getCgstPercent().add(row.getSgstPercent());
        BigDecimal rowCgst = tax.multiply(row.getCgstPercent()).divide(rate, 2, RoundingMode.HALF_UP);
        row.setCgstAmount(rowCgst);
        row.setSgstAmount(tax.subtract(rowCgst));
        cgst = cgst.add(row.getCgstAmount());
        sgst = sgst.add(row.getSgstAmount());
      }
    }

    /** Only a bill whose every line states its amount, and that charges some tax, is worked from its lines. */
    boolean isUsable() {
      return everyLineHasAmount && !rows.isEmpty();
    }
  }

  private static BigDecimal sumTaxRates(String cgst, String sgst) {
    BigDecimal total = BigDecimal.ZERO;
    total = total.add(parseTaxRate(cgst));
    total = total.add(parseTaxRate(sgst));
    return total;
  }

  private static BigDecimal parseTaxRate(String rate) {
    if (rate == null || rate.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(rate.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}

