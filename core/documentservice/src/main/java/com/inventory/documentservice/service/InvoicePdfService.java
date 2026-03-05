package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Service for generating invoice PDFs using Thymeleaf templates and OpenHTMLToPDF.
 */
@Service
@Slf4j
public class InvoicePdfService {

  @Autowired
  private TemplateEngine templateEngine;

  /**
   * Generate invoice PDF from purchase data using Thymeleaf template.
   *
   * @param request the invoice generation request containing all invoice data
   * @return PDF as byte array
   */
  public byte[] generateInvoicePdf(GenerateInvoiceRequest request) {
    try {
      log.debug("Generating invoice PDF for invoice: {}", request.getInvoiceNo());

      // Prepare template context
      Context context = prepareTemplateContext(request);

      // Render HTML from template
      String html = templateEngine.process("invoice/invoice", context);

      // Convert HTML to PDF
      return convertHtmlToPdf(html);

    } catch (Exception e) {
      log.error("Error generating invoice PDF: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to generate invoice PDF", e);
    }
  }

  private Context prepareTemplateContext(GenerateInvoiceRequest request) {
    Context context = new Context();

    // Basic invoice data
    context.setVariable("invoiceNo", request.getInvoiceNo() != null ? request.getInvoiceNo() : "");
    context.setVariable("invoiceDate", request.getInvoiceDate() != null ? request.getInvoiceDate() : formatDate(request.getSoldAt()));
    context.setVariable("invoiceTime", request.getInvoiceTime() != null ? request.getInvoiceTime() : formatTime(request.getSoldAt()));
    context.setVariable("billingMode", request.getBillingMode() != null ? request.getBillingMode() : "REGULAR");
    context.setVariable("showSellerDetails", request.getShowSellerDetails() == null || request.getShowSellerDetails());
    context.setVariable("showBuyerDetails", request.getShowBuyerDetails() == null || request.getShowBuyerDetails());
    context.setVariable("showTaxDetails", request.getShowTaxDetails() == null || request.getShowTaxDetails());
    context.setVariable("showScheme", request.getShowScheme() == null || request.getShowScheme());
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
    context.setVariable("subTotal", request.getSubTotal() != null ? request.getSubTotal() : BigDecimal.ZERO);
    context.setVariable("discountTotal", request.getDiscountTotal() != null ? request.getDiscountTotal() : BigDecimal.ZERO);
    context.setVariable("additionalDiscountTotal", request.getAdditionalDiscountTotal() != null ? request.getAdditionalDiscountTotal() : BigDecimal.ZERO);
    context.setVariable("sgstAmount", request.getSgstAmount() != null ? request.getSgstAmount() : BigDecimal.ZERO);
    context.setVariable("cgstAmount", request.getCgstAmount() != null ? request.getCgstAmount() : BigDecimal.ZERO);
    context.setVariable("sgstPercent", request.getSgstPercent() != null ? request.getSgstPercent() : BigDecimal.valueOf(2.5));
    context.setVariable("cgstPercent", request.getCgstPercent() != null ? request.getCgstPercent() : BigDecimal.valueOf(2.5));
    context.setVariable("taxTotal", request.getTaxTotal() != null ? request.getTaxTotal() : BigDecimal.ZERO);
    context.setVariable("roundOff", request.getRoundOff() != null ? request.getRoundOff() : BigDecimal.ZERO);
    context.setVariable("grandTotal", request.getGrandTotal() != null ? request.getGrandTotal() : BigDecimal.ZERO);
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
    LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    return dateTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
  }

  private String formatTime(Instant instant) {
    if (instant == null) {
      return "";
    }
    LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    return dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
  }

  private byte[] convertHtmlToPdf(String html) throws IOException {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.withHtmlContent(html, null);
      builder.toStream(outputStream);
      builder.run();

      return outputStream.toByteArray();
    }
  }
}
