package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.product.utils.AmountToWordsConverter;
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

    PurchaseInvoiceContext context = loadAndValidate(purchaseId, shopId);
    GenerateInvoiceRequest request =
        buildGenerateInvoiceRequest(context.purchase(), context.shop(), context.settings());

    String resolvedPrinter =
        (printerType != null && !printerType.isBlank())
            ? printerType
            : context.settings().getDefaultPrinterType();
    request.setPrinterType(resolvedPrinter);

    byte[] pdf = documentService.generateInvoice(request);
    if (metrics != null) {
      metrics.record(ProductMetricsConstants.INVOICES_GENERATED, 1, "module", ProductMetricsConstants.MODULE);
    }
    return pdf;
  }

  /**
   * Render the invoice as fixed-width plain text for the dot matrix print bridge.
   *
   * @param purchaseId the purchase ID
   * @param shopId the shop ID for validation
   * @return 80-column plain text
   */
  public String generateInvoiceText(String purchaseId, String shopId) {
    log.info("Generating invoice text for purchase: {}, shop: {}", purchaseId, shopId);

    PurchaseInvoiceContext context = loadAndValidate(purchaseId, shopId);
    GenerateInvoiceRequest request =
        buildGenerateInvoiceRequest(context.purchase(), context.shop(), context.settings());
    request.setPrinterType("DOT_MATRIX");

    String text = documentService.generateInvoiceText(request);
    if (metrics != null) {
      metrics.record(
          ProductMetricsConstants.INVOICES_GENERATED, 1, "module", ProductMetricsConstants.MODULE);
    }
    return text;
  }

  /**
   * Look up the purchase and its shop, verify the purchase belongs to {@code shopId}, and
   * resolve the shop's invoice settings. Shared by every invoice-rendering entry point
   * (PDF, text, ...) so the lookup and shop-scoping logic exists exactly once.
   *
   * @param purchaseId the purchase ID
   * @param shopId the shop ID the purchase must belong to
   * @return the purchase, its shop, and the shop's invoice settings
   * @throws ResourceNotFoundException if the purchase or shop cannot be found
   * @throws ValidationException if the purchase does not belong to {@code shopId}
   */
  private PurchaseInvoiceContext loadAndValidate(String purchaseId, String shopId) {
    Purchase purchase = purchaseRepository.findById(purchaseId)
        .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id", purchaseId));

    if (!shopId.equals(purchase.getShopId())) {
      throw new ValidationException("Purchase does not belong to the specified shop");
    }

    Shop shop = shopRepository.findById(purchase.getShopId())
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", purchase.getShopId()));

    var settings = invoiceSettingsService.getOrDefaultForShop(shopId);
    return new PurchaseInvoiceContext(purchase, shop, settings);
  }

  /** Purchase, shop, and invoice settings resolved and shop-validated by {@link #loadAndValidate}. */
  private record PurchaseInvoiceContext(
      Purchase purchase,
      Shop shop,
      com.inventory.product.domain.model.ShopInvoiceSettingsDocument settings) {}

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
        request.setCustomerName(customer.getName());
        request.setCustomerAddress(customer.getAddress());
        request.setCustomerDlNo(customer.getDlNo());
        request.setCustomerGstin(customer.getGstin());
        request.setCustomerPan(customer.getPan());
        request.setCustomerPhone(customer.getPhone());
        request.setCustomerEmail(customer.getEmail());
      }
    } else if (purchase.getCustomerName() != null && !purchase.getCustomerName().isEmpty()) {
      request.setCustomerName(purchase.getCustomerName());
    }

    List<InvoiceItem> invoiceItems = new ArrayList<>();
    if (purchase.getItems() != null) {
      for (PurchaseItem purchaseItem : purchase.getItems()) {
        InvoiceItem invoiceItem = new InvoiceItem();
        invoiceItem.setQuantity(purchaseItem.getQuantity());
        invoiceItem.setName(purchaseItem.getName());
        invoiceItem.setMaximumRetailPrice(purchaseItem.getMaximumRetailPrice());
        invoiceItem.setPriceToRetail(purchaseItem.getPriceToRetail());
        invoiceItem.setDiscount(purchaseItem.getDiscount());
        invoiceItem.setSaleAdditionalDiscount(purchaseItem.getSaleAdditionalDiscount());
        invoiceItem.setTotalAmount(purchaseItem.getTotalAmount());
        invoiceItem.setCgst(purchaseItem.getCgst());
        invoiceItem.setSgst(purchaseItem.getSgst());
        invoiceItem.setGstPercent(sumTaxRates(purchaseItem.getCgst(), purchaseItem.getSgst()));
        invoiceItem.setInventoryId(purchaseItem.getInventoryId());
        invoiceItem.setSchemePayFor(purchaseItem.getSchemePayFor());
        invoiceItem.setSchemeFree(purchaseItem.getSchemeFree());
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

    request.setTaxTotal(purchase.getTaxTotal() != null ? purchase.getTaxTotal() : BigDecimal.ZERO);

    BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
    BigDecimal calculatedTotal = request.getSubTotal()
        .subtract(request.getDiscountTotal())
        .add(request.getTaxTotal());
    request.setRoundOff(grandTotal.subtract(calculatedTotal));
    request.setGrandTotal(grandTotal);
    request.setTotalAmountSaved(totalMRPAmount.subtract(grandTotal));

    request.setPaymentMethod(purchase.getPaymentMethod());
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(grandTotal));
    request.setSoldAt(purchase.getSoldAt());

    return request;
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

