package com.inventory.product.service;

import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.InvoiceFieldVisibility;
import com.inventory.product.domain.model.InvoiceSettingsDefaults;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.repository.ShopInvoiceSettingsRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.PreviewInvoiceSettingsRequest;
import com.inventory.product.rest.dto.request.UpdateInvoiceSettingsRequest;
import com.inventory.product.rest.dto.response.InvoiceSettingsResponse;
import com.inventory.product.utils.AmountToWordsConverter;
import com.inventory.product.validation.ShopValidator;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.user.service.UserShopMembershipService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Shop-level invoice template defaults and live PDF preview.
 */
@Service
public class InvoiceSettingsService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Autowired
  private ShopInvoiceSettingsRepository settingsRepository;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private UserShopMembershipService membershipService;

  @Autowired
  private ShopValidator shopValidator;

  @Autowired
  private DocumentService documentService;

  public InvoiceSettingsResponse getSettings(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    return toResponse(resolvedDocument(shopId));
  }

  public InvoiceSettingsResponse updateSettings(
      String shopId, String userId, UpdateInvoiceSettingsRequest request) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    if (request == null) {
      return toResponse(resolvedDocument(shopId));
    }

    ShopInvoiceSettingsDocument doc =
        settingsRepository.findByShopId(shopId).orElseGet(() -> {
          ShopInvoiceSettingsDocument created = InvoiceSettingsDefaults.unsavedDefaults(shopId);
          return created;
        });

    if (StringUtils.hasText(request.getDefaultPrinterType())) {
      doc.setDefaultPrinterType(PrinterType.from(request.getDefaultPrinterType()).name());
    }
    if (request.getFooterNote() != null) {
      doc.setFooterNote(request.getFooterNote().trim());
    }
    if (request.getRegularFields() != null) {
      doc.setRegularFields(
          InvoiceSettingsDefaults.resolve(
              request.getRegularFields(), InvoiceSettingsDefaults.regularFields()));
    }
    if (request.getBasicFields() != null) {
      doc.setBasicFields(
          InvoiceSettingsDefaults.resolve(
              request.getBasicFields(), InvoiceSettingsDefaults.basicFields()));
    }

    doc.setShopId(shopId);
    doc.setUpdatedAt(Instant.now());
    doc.setUpdatedByUserId(userId);
    return toResponse(settingsRepository.save(doc));
  }

  /**
   * Resolved settings for PDF generation (no membership check — caller already scoped by shop).
   */
  public ShopInvoiceSettingsDocument getOrDefaultForShop(String shopId) {
    return resolvedDocument(shopId);
  }

  public InvoiceFieldVisibility fieldsForMode(
      ShopInvoiceSettingsDocument settings, BillingMode billingMode) {
    boolean basic = billingMode == BillingMode.BASIC;
    InvoiceFieldVisibility stored =
        basic ? settings.getBasicFields() : settings.getRegularFields();
    InvoiceFieldVisibility defaults =
        basic ? InvoiceSettingsDefaults.basicFields() : InvoiceSettingsDefaults.regularFields();
    return InvoiceSettingsDefaults.resolve(stored, defaults);
  }

  public void applyVisibility(GenerateInvoiceRequest request, InvoiceFieldVisibility fields) {
    if (request == null || fields == null) {
      return;
    }
    request.setShowSellerDetails(Boolean.TRUE.equals(fields.getShowSellerDetails()));
    request.setShowShopName(Boolean.TRUE.equals(fields.getShowShopName()));
    request.setShowShopAddress(Boolean.TRUE.equals(fields.getShowShopAddress()));
    request.setShowShopTagline(Boolean.TRUE.equals(fields.getShowShopTagline()));
    request.setShowShopPhone(Boolean.TRUE.equals(fields.getShowShopPhone()));
    request.setShowShopEmail(Boolean.TRUE.equals(fields.getShowShopEmail()));
    request.setShowShopGstin(Boolean.TRUE.equals(fields.getShowShopGstin()));
    request.setShowShopPan(Boolean.TRUE.equals(fields.getShowShopPan()));
    request.setShowShopDlNo(Boolean.TRUE.equals(fields.getShowShopDlNo()));
    request.setShowShopFssai(Boolean.TRUE.equals(fields.getShowShopFssai()));
    request.setShowBuyerDetails(Boolean.TRUE.equals(fields.getShowBuyerDetails()));
    request.setShowCustomerName(Boolean.TRUE.equals(fields.getShowCustomerName()));
    request.setShowCustomerAddress(Boolean.TRUE.equals(fields.getShowCustomerAddress()));
    request.setShowCustomerPhone(Boolean.TRUE.equals(fields.getShowCustomerPhone()));
    request.setShowCustomerEmail(Boolean.TRUE.equals(fields.getShowCustomerEmail()));
    request.setShowCustomerGstin(Boolean.TRUE.equals(fields.getShowCustomerGstin()));
    request.setShowCustomerPan(Boolean.TRUE.equals(fields.getShowCustomerPan()));
    request.setShowCustomerDlNo(Boolean.TRUE.equals(fields.getShowCustomerDlNo()));
    request.setShowPaymentMethod(Boolean.TRUE.equals(fields.getShowPaymentMethod()));
    request.setShowTaxDetails(Boolean.TRUE.equals(fields.getShowTaxDetails()));
    request.setShowAmountInWords(Boolean.TRUE.equals(fields.getShowAmountInWords()));
    request.setShowAmountSaved(Boolean.TRUE.equals(fields.getShowAmountSaved()));
    request.setShowAdditionalDiscount(Boolean.TRUE.equals(fields.getShowAdditionalDiscount()));
    request.setShowHsn(Boolean.TRUE.equals(fields.getShowHsn()));
    request.setShowMfg(Boolean.TRUE.equals(fields.getShowMfg()));
    request.setShowExpiry(Boolean.TRUE.equals(fields.getShowExpiry()));
    request.setShowBatch(Boolean.TRUE.equals(fields.getShowBatch()));
    request.setShowMrp(Boolean.TRUE.equals(fields.getShowMrp()));
    request.setShowScheme(Boolean.TRUE.equals(fields.getShowScheme()));
    request.setShowLineDiscount(Boolean.TRUE.equals(fields.getShowLineDiscount()));
    request.setShowSignatures(Boolean.TRUE.equals(fields.getShowSignatures()));
  }

  /** Apply the relevant subset of invoice field visibility to a credit-note print request. */
  public void applyCreditNoteVisibility(
      com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest request,
      InvoiceFieldVisibility fields) {
    if (request == null || fields == null) {
      return;
    }
    request.setShowSellerDetails(Boolean.TRUE.equals(fields.getShowSellerDetails()));
    request.setShowShopName(Boolean.TRUE.equals(fields.getShowShopName()));
    request.setShowShopAddress(Boolean.TRUE.equals(fields.getShowShopAddress()));
    request.setShowShopTagline(Boolean.TRUE.equals(fields.getShowShopTagline()));
    request.setShowShopPhone(Boolean.TRUE.equals(fields.getShowShopPhone()));
    request.setShowShopEmail(Boolean.TRUE.equals(fields.getShowShopEmail()));
    request.setShowShopGstin(Boolean.TRUE.equals(fields.getShowShopGstin()));
    request.setShowShopPan(Boolean.TRUE.equals(fields.getShowShopPan()));
    request.setShowShopDlNo(Boolean.TRUE.equals(fields.getShowShopDlNo()));
    request.setShowShopFssai(Boolean.TRUE.equals(fields.getShowShopFssai()));
    request.setShowBuyerDetails(Boolean.TRUE.equals(fields.getShowBuyerDetails()));
    request.setShowCustomerName(Boolean.TRUE.equals(fields.getShowCustomerName()));
    request.setShowCustomerAddress(Boolean.TRUE.equals(fields.getShowCustomerAddress()));
    request.setShowCustomerPhone(Boolean.TRUE.equals(fields.getShowCustomerPhone()));
    request.setShowCustomerEmail(Boolean.TRUE.equals(fields.getShowCustomerEmail()));
    request.setShowCustomerGstin(Boolean.TRUE.equals(fields.getShowCustomerGstin()));
    request.setShowCustomerPan(Boolean.TRUE.equals(fields.getShowCustomerPan()));
    request.setShowCustomerDlNo(Boolean.TRUE.equals(fields.getShowCustomerDlNo()));
    request.setShowPaymentMethod(Boolean.TRUE.equals(fields.getShowPaymentMethod()));
    request.setShowTaxDetails(Boolean.TRUE.equals(fields.getShowTaxDetails()));
    request.setShowAmountInWords(Boolean.TRUE.equals(fields.getShowAmountInWords()));
    request.setShowHsn(Boolean.TRUE.equals(fields.getShowHsn()));
    request.setShowMfg(Boolean.TRUE.equals(fields.getShowMfg()));
    request.setShowBatch(Boolean.TRUE.equals(fields.getShowBatch()));
    request.setShowSignatures(Boolean.TRUE.equals(fields.getShowSignatures()));
  }

  public String previewHtml(
      String shopId, String userId, PreviewInvoiceSettingsRequest request) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));

    BillingMode mode = parseBillingMode(
        request != null ? request.getPreviewBillingMode() : null);
    String printerType = resolvePreviewPrinter(request);

    ShopInvoiceSettingsDocument draft = draftFromPreview(shopId, request);
    InvoiceFieldVisibility fields = fieldsForMode(draft, mode);

    GenerateInvoiceRequest invoice = buildSampleInvoice(shop, mode);
    applyVisibility(invoice, fields);
    invoice.setFooterNote(draft.getFooterNote() != null ? draft.getFooterNote() : "");
    invoice.setPrinterType(printerType);

    return documentService.generateInvoiceHtml(invoice);
  }

  private ShopInvoiceSettingsDocument draftFromPreview(
      String shopId, PreviewInvoiceSettingsRequest request) {
    ShopInvoiceSettingsDocument base = resolvedDocument(shopId);
    if (request == null) {
      return base;
    }
    if (StringUtils.hasText(request.getDefaultPrinterType())) {
      base.setDefaultPrinterType(PrinterType.from(request.getDefaultPrinterType()).name());
    }
    if (request.getFooterNote() != null) {
      base.setFooterNote(request.getFooterNote());
    }
    if (request.getRegularFields() != null) {
      base.setRegularFields(
          InvoiceSettingsDefaults.resolve(
              request.getRegularFields(), InvoiceSettingsDefaults.regularFields()));
    }
    if (request.getBasicFields() != null) {
      base.setBasicFields(
          InvoiceSettingsDefaults.resolve(
              request.getBasicFields(), InvoiceSettingsDefaults.basicFields()));
    }
    return base;
  }

  private String resolvePreviewPrinter(PreviewInvoiceSettingsRequest request) {
    if (request == null) {
      return InvoiceSettingsDefaults.DEFAULT_PRINTER_TYPE;
    }
    if (StringUtils.hasText(request.getPreviewPrinterType())) {
      return PrinterType.from(request.getPreviewPrinterType()).name();
    }
    if (StringUtils.hasText(request.getDefaultPrinterType())) {
      return PrinterType.from(request.getDefaultPrinterType()).name();
    }
    return InvoiceSettingsDefaults.DEFAULT_PRINTER_TYPE;
  }

  private ShopInvoiceSettingsDocument resolvedDocument(String shopId) {
    return settingsRepository
        .findByShopId(shopId)
        .map(this::hydrateResolved)
        .orElseGet(() -> InvoiceSettingsDefaults.unsavedDefaults(shopId));
  }

  private ShopInvoiceSettingsDocument hydrateResolved(ShopInvoiceSettingsDocument doc) {
    if (!StringUtils.hasText(doc.getDefaultPrinterType())) {
      doc.setDefaultPrinterType(InvoiceSettingsDefaults.DEFAULT_PRINTER_TYPE);
    } else {
      doc.setDefaultPrinterType(PrinterType.from(doc.getDefaultPrinterType()).name());
    }
    if (doc.getFooterNote() == null) {
      doc.setFooterNote("");
    }
    doc.setRegularFields(
        InvoiceSettingsDefaults.resolve(
            doc.getRegularFields(), InvoiceSettingsDefaults.regularFields()));
    doc.setBasicFields(
        InvoiceSettingsDefaults.resolve(
            doc.getBasicFields(), InvoiceSettingsDefaults.basicFields()));
    return doc;
  }

  private InvoiceSettingsResponse toResponse(ShopInvoiceSettingsDocument doc) {
    ShopInvoiceSettingsDocument resolved = hydrateResolved(copyDoc(doc));
    return new InvoiceSettingsResponse(
        resolved.getShopId(),
        resolved.getDefaultPrinterType(),
        resolved.getFooterNote(),
        resolved.getRegularFields(),
        resolved.getBasicFields());
  }

  private static ShopInvoiceSettingsDocument copyDoc(ShopInvoiceSettingsDocument src) {
    ShopInvoiceSettingsDocument copy = new ShopInvoiceSettingsDocument();
    copy.setId(src.getId());
    copy.setShopId(src.getShopId());
    copy.setDefaultPrinterType(src.getDefaultPrinterType());
    copy.setFooterNote(src.getFooterNote());
    copy.setRegularFields(InvoiceSettingsDefaults.copy(src.getRegularFields()));
    copy.setBasicFields(InvoiceSettingsDefaults.copy(src.getBasicFields()));
    copy.setUpdatedAt(src.getUpdatedAt());
    copy.setUpdatedByUserId(src.getUpdatedByUserId());
    return copy;
  }

  private static BillingMode parseBillingMode(String value) {
    if (value != null && value.trim().equalsIgnoreCase(BillingMode.BASIC.name())) {
      return BillingMode.BASIC;
    }
    return BillingMode.REGULAR;
  }

  GenerateInvoiceRequest buildSampleInvoice(Shop shop, BillingMode billingMode) {
    GenerateInvoiceRequest request = new GenerateInvoiceRequest();
    boolean basic = billingMode == BillingMode.BASIC;
    request.setBillingMode(billingMode.name());
    request.setInvoiceNo(basic ? "BSC-00001" : "INV-00001");

    LocalDateTime now = LocalDateTime.now(IST);
    request.setInvoiceDate(now.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
    request.setInvoiceTime(now.format(DateTimeFormatter.ofPattern("hh:mm a")));
    request.setSoldAt(Instant.now());

    request.setShopName(shop.getName() != null ? shop.getName() : "Sample Shop");
    request.setShopAddress(formatShopAddress(shop));
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
    if (shop.getLocation() != null && StringUtils.hasText(shop.getLocation().getState())) {
      request.setPlaceOfSupply(shop.getLocation().getState());
    }

    request.setCustomerName("Sample Customer");
    request.setCustomerAddress("12 Sample Street, Sample City");
    request.setCustomerDlNo("DL-SAMPLE-001");
    request.setCustomerGstin("29AABCU9603R1ZM");
    request.setCustomerPan("AABCU9603R");
    request.setCustomerPhone("9876543210");
    request.setCustomerEmail("customer@example.com");

    List<InvoiceItem> items = new ArrayList<>();
    items.add(
        sampleItem(
            "Sample Medicine A",
            "30049099",
            "Acme Pharma",
            "12/27",
            "BATCH-A1",
            bd("120.00"),
            bd("100.00"),
            bd("2.00"),
            bd("200.00"),
            2,
            10,
            1,
            "2.5",
            "2.5"));
    items.add(
        sampleItem(
            "Sample Medicine B",
            "30041010",
            "Beta Labs",
            "06/28",
            "BATCH-B2",
            bd("80.00"),
            bd("70.00"),
            bd("0.00"),
            bd("70.00"),
            1,
            null,
            null,
            "2.5",
            "2.5"));
    request.setItems(items);

    BigDecimal subTotal = bd("270.00");
    BigDecimal additionalDiscount = bd("5.00");
    BigDecimal sgst = bd("6.63");
    BigDecimal cgst = bd("6.63");
    BigDecimal taxTotal = sgst.add(cgst);
    BigDecimal grandTotal = subTotal.subtract(additionalDiscount).add(taxTotal);
    BigDecimal totalMrp = bd("320.00");

    request.setSubTotal(subTotal);
    request.setDiscountTotal(BigDecimal.ZERO);
    request.setSaleAdditionalDiscountTotal(additionalDiscount);
    request.setSgstAmount(sgst);
    request.setCgstAmount(cgst);
    request.setSgstPercent(bd("2.5"));
    request.setCgstPercent(bd("2.5"));
    request.setTaxTotal(taxTotal);
    request.setRoundOff(BigDecimal.ZERO);
    request.setGrandTotal(grandTotal);
    request.setTotalMRPAmount(totalMrp);
    request.setTotalAmountSaved(totalMrp.subtract(grandTotal));
    request.setPaymentMethod("CASH");
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(grandTotal));
    request.setFooterNote("");

    return request;
  }

  private static InvoiceItem sampleItem(
      String name,
      String hsn,
      String company,
      String expiry,
      String batch,
      BigDecimal mrp,
      BigDecimal rate,
      BigDecimal lineDiscount,
      BigDecimal total,
      int qty,
      Integer schemePayFor,
      Integer schemeFree,
      String cgst,
      String sgst) {
    InvoiceItem item = new InvoiceItem();
    item.setName(name);
    item.setHsn(hsn);
    item.setCompanyName(company);
    item.setExpiryDate(expiry);
    item.setBatchNo(batch);
    item.setMaximumRetailPrice(mrp);
    item.setPriceToRetail(rate);
    item.setSaleAdditionalDiscount(lineDiscount);
    item.setTotalAmount(total);
    item.setQuantity(BigDecimal.valueOf(qty));
    item.setSchemePayFor(schemePayFor);
    item.setSchemeFree(schemeFree);
    if (schemeFree != null) {
      item.setScheme(schemeFree);
    }
    item.setCgst(cgst);
    item.setSgst(sgst);
    item.setGstPercent(bd(cgst).add(bd(sgst)));
    return item;
  }

  private static String formatShopAddress(Shop shop) {
    if (shop.getLocation() == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    if (StringUtils.hasText(shop.getLocation().getPrimaryAddress())) {
      parts.add(shop.getLocation().getPrimaryAddress());
    }
    if (StringUtils.hasText(shop.getLocation().getSecondaryAddress())) {
      parts.add(shop.getLocation().getSecondaryAddress());
    }
    if (StringUtils.hasText(shop.getLocation().getCity())) {
      parts.add(shop.getLocation().getCity());
    }
    if (StringUtils.hasText(shop.getLocation().getState())) {
      parts.add(shop.getLocation().getState());
    }
    if (StringUtils.hasText(shop.getLocation().getPin())) {
      parts.add(shop.getLocation().getPin());
    }
    return String.join(", ", parts);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
  }
}
