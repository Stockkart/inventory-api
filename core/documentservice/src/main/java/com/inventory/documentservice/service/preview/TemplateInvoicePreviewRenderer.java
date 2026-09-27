package com.inventory.documentservice.service.preview;

import com.inventory.documentservice.domain.DocumentTemplateFamily;
import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Previews an invoice by rendering the Thymeleaf markup its printer's template describes.
 *
 * <p>This is the fallback: it answers for every printer that has no more specific renderer, which
 * is why it is ordered last. A printer whose preview is genuinely page markup needs only a
 * template, not a class.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class TemplateInvoicePreviewRenderer implements InvoicePreviewRenderer {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final TemplateEngine templateEngine;

  public TemplateInvoicePreviewRenderer(TemplateEngine templateEngine) {
    this.templateEngine = templateEngine;
  }

  @Override
  public boolean supports(PrinterType printerType) {
    return true;
  }

  @Override
  public String render(GenerateInvoiceRequest request) {
    PrinterType printerType = PrinterType.from(request.getPrinterType());
    Context context = prepareTemplateContext(request);
    String templateName = printerType.getTemplateName(DocumentTemplateFamily.INVOICE);
    log.debug("Rendering invoice HTML with printer type {} -> template {}", printerType, templateName);
    return templateEngine.process(templateName, context);
  }

  private Context prepareTemplateContext(GenerateInvoiceRequest request) {
    Context context = new Context();

    // Basic invoice data
    context.setVariable("invoiceNo", request.getInvoiceNo() != null ? request.getInvoiceNo() : "");
    context.setVariable("invoiceDate", request.getInvoiceDate() != null ? request.getInvoiceDate() : formatDate(request.getSoldAt()));
    context.setVariable("invoiceTime", request.getInvoiceTime() != null ? request.getInvoiceTime() : formatTime(request.getSoldAt()));
    context.setVariable("billingMode", request.getBillingMode() != null ? request.getBillingMode() : "REGULAR");
    String documentType = request.getDocumentType() != null ? request.getDocumentType() : "SALE";
    String billingMode = request.getBillingMode() != null ? request.getBillingMode() : "REGULAR";
    boolean isEstimate =
        "ESTIMATE".equalsIgnoreCase(documentType) || "BASIC".equalsIgnoreCase(billingMode);
    context.setVariable("documentType", documentType);
    context.setVariable("isEstimate", isEstimate);
    context.setVariable("showSellerDetails", visible(request.getShowSellerDetails()));
    context.setVariable("showShopName", visible(request.getShowShopName()));
    context.setVariable("showShopAddress", visible(request.getShowShopAddress()));
    context.setVariable("showShopTagline", visible(request.getShowShopTagline()));
    context.setVariable("showShopPhone", visible(request.getShowShopPhone()));
    context.setVariable("showShopEmail", visible(request.getShowShopEmail()));
    context.setVariable("showShopGstin", visible(request.getShowShopGstin()));
    context.setVariable("showShopPan", visible(request.getShowShopPan()));
    context.setVariable("showShopDlNo", visible(request.getShowShopDlNo()));
    context.setVariable("showShopFssai", visible(request.getShowShopFssai()));
    context.setVariable("showBuyerDetails", visible(request.getShowBuyerDetails()));
    context.setVariable("showCustomerName", visible(request.getShowCustomerName()));
    context.setVariable("showCustomerAddress", visible(request.getShowCustomerAddress()));
    context.setVariable("showCustomerPhone", visible(request.getShowCustomerPhone()));
    context.setVariable("showCustomerEmail", visible(request.getShowCustomerEmail()));
    context.setVariable("showCustomerGstin", visible(request.getShowCustomerGstin()));
    context.setVariable("showCustomerPan", visible(request.getShowCustomerPan()));
    context.setVariable("showCustomerDlNo", visible(request.getShowCustomerDlNo()));
    context.setVariable("showTaxDetails", request.getShowTaxDetails() == null || request.getShowTaxDetails());
    context.setVariable("showScheme", request.getShowScheme() == null || request.getShowScheme());
    context.setVariable("showPaymentMethod", request.getShowPaymentMethod() == null || request.getShowPaymentMethod());
    context.setVariable("showAmountInWords", request.getShowAmountInWords() == null || request.getShowAmountInWords());
    context.setVariable("showAmountSaved", request.getShowAmountSaved() == null || request.getShowAmountSaved());
    context.setVariable(
        "showAdditionalDiscount",
        request.getShowAdditionalDiscount() == null || request.getShowAdditionalDiscount());
    context.setVariable("showHsn", request.getShowHsn() == null || request.getShowHsn());
    context.setVariable("showMfg", request.getShowMfg() == null || request.getShowMfg());
    context.setVariable("showExpiry", request.getShowExpiry() == null || request.getShowExpiry());
    context.setVariable("showBatch", request.getShowBatch() == null || request.getShowBatch());
    context.setVariable("showMrp", request.getShowMrp() == null || request.getShowMrp());
    context.setVariable(
        "showLineDiscount", request.getShowLineDiscount() == null || request.getShowLineDiscount());
    context.setVariable(
        "showSignatures", request.getShowSignatures() == null || request.getShowSignatures());
    context.setVariable("paymentMethod", request.getPaymentMethod());

    // Shop/Seller details
    context.setVariable("shopName", request.getShopName());
    context.setVariable("shopAddress", request.getShopAddress());
    context.setVariable("shopDlNo", request.getShopDlNo());
    context.setVariable("shopFssai", request.getShopFssai());
    context.setVariable("shopGstin", request.getShopGstin());
    context.setVariable("shopPhone", request.getShopPhone());
    context.setVariable("shopEmail", request.getShopEmail());
    context.setVariable("shopTagline", request.getShopTagline());
    context.setVariable("shopPan", request.getShopPan());
    context.setVariable("placeOfSupply", request.getPlaceOfSupply());

    // Customer/Buyer details
    context.setVariable("customerName", request.getCustomerName());
    context.setVariable("customerAddress", request.getCustomerAddress());
    context.setVariable("customerDlNo", request.getCustomerDlNo());
    context.setVariable("customerGstin", request.getCustomerGstin());
    context.setVariable("customerPan", request.getCustomerPan());
    context.setVariable("customerPhone", request.getCustomerPhone());
    context.setVariable("customerEmail", request.getCustomerEmail());

    // Items
    context.setVariable("items", request.getItems());

    // Totals
    BigDecimal subTotal = request.getSubTotal() != null ? request.getSubTotal() : BigDecimal.ZERO;
    BigDecimal taxTotal = request.getTaxTotal() != null ? request.getTaxTotal() : BigDecimal.ZERO;
    BigDecimal grandTotal = request.getGrandTotal() != null ? request.getGrandTotal() : BigDecimal.ZERO;
    context.setVariable("subTotal", subTotal);
    context.setVariable("discountTotal", request.getDiscountTotal() != null ? request.getDiscountTotal() : BigDecimal.ZERO);
    context.setVariable("additionalDiscountTotal", request.getSaleAdditionalDiscountTotal() != null ? request.getSaleAdditionalDiscountTotal() : BigDecimal.ZERO);
    context.setVariable("sgstAmount", request.getSgstAmount() != null ? request.getSgstAmount() : BigDecimal.ZERO);
    context.setVariable("cgstAmount", request.getCgstAmount() != null ? request.getCgstAmount() : BigDecimal.ZERO);
    context.setVariable("sgstPercent", request.getSgstPercent() != null ? request.getSgstPercent() : BigDecimal.valueOf(2.5));
    context.setVariable("cgstPercent", request.getCgstPercent() != null ? request.getCgstPercent() : BigDecimal.valueOf(2.5));
    context.setVariable("taxTotal", taxTotal);
    context.setVariable("taxableAmount", grandTotal.subtract(taxTotal).max(BigDecimal.ZERO));
    context.setVariable("roundOff", request.getRoundOff() != null ? request.getRoundOff() : BigDecimal.ZERO);
    context.setVariable("grandTotal", grandTotal);
    context.setVariable("totalMRPAmount", request.getTotalMRPAmount() != null ? request.getTotalMRPAmount() : BigDecimal.ZERO);
    context.setVariable("totalAmountSaved", request.getTotalAmountSaved() != null ? request.getTotalAmountSaved() : BigDecimal.ZERO);

    // Additional fields
    context.setVariable("amountInWords", request.getAmountInWords());
    context.setVariable("footerNote", request.getFooterNote());

    return context;
  }

  private String formatDate(Instant instant) {
    if (instant == null) {
      return "";
    }
    LocalDateTime dateTime = LocalDateTime.ofInstant(instant, IST);
    return dateTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
  }

  private String formatTime(Instant instant) {
    if (instant == null) {
      return "";
    }
    LocalDateTime dateTime = LocalDateTime.ofInstant(instant, IST);
    return dateTime.format(DateTimeFormatter.ofPattern("hh:mm a"));
  }

  private static boolean visible(Boolean flag) {
    return flag == null || flag;
  }}
