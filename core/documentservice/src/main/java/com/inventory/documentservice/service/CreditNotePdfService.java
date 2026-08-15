package com.inventory.documentservice.service;

import com.inventory.documentservice.domain.DocumentTemplateFamily;
import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;

/**
 * Generates credit-note PDFs using Thymeleaf templates and OpenHTMLToPDF.
 */
@Service
@Slf4j
public class CreditNotePdfService {

  @Autowired
  private TemplateEngine templateEngine;

  @Autowired
  private HtmlToPdfConverter htmlToPdfConverter;

  public byte[] generateCreditNotePdf(GenerateCreditNoteRequest request) {
    try {
      log.debug("Generating credit note PDF: {}", request != null ? request.getCreditNoteNo() : null);
      String html = renderCreditNoteHtml(request);
      return htmlToPdfConverter.convert(html);
    } catch (Exception e) {
      log.error("Error generating credit note PDF: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to generate credit note PDF", e);
    }
  }

  public String renderCreditNoteHtml(GenerateCreditNoteRequest request) {
    Context context = prepareTemplateContext(request);
    PrinterType printerType = PrinterType.from(request != null ? request.getPrinterType() : null);
    String templateName = printerType.getTemplateName(DocumentTemplateFamily.CREDIT_NOTE);
    log.debug(
        "Rendering credit note HTML with printer type {} → template {}", printerType, templateName);
    return templateEngine.process(templateName, context);
  }

  private Context prepareTemplateContext(GenerateCreditNoteRequest request) {
    Context context = new Context();
    if (request == null) {
      request = new GenerateCreditNoteRequest();
    }

    context.setVariable("creditNoteNo", nullToEmpty(request.getCreditNoteNo()));
    context.setVariable("noteDate", nullToEmpty(request.getNoteDate()));
    context.setVariable("noteTime", nullToEmpty(request.getNoteTime()));
    context.setVariable("againstInvoiceNo", nullToEmpty(request.getAgainstInvoiceNo()));
    context.setVariable(
        "partyRole",
        request.getPartyRole() != null && !request.getPartyRole().isBlank()
            ? request.getPartyRole().trim().toUpperCase()
            : "CUSTOMER");
    boolean vendorNote = "VENDOR".equals(context.getVariable("partyRole"));
    context.setVariable("documentTitle", vendorNote ? "Debit Note" : "Credit Note");
    context.setVariable(
        "documentNoLabel", vendorNote ? "Debit Note No." : "Credit Note No.");
    context.setVariable(
        "documentAmountLabel", vendorNote ? "Debit Note Amount" : "Credit Note Amount");
    context.setVariable(
        "documentAmountLabelShort", vendorNote ? "Debit Note Amt" : "Credit Note Amt");
    context.setVariable("documentNoShort", vendorNote ? "DN" : "CN");

    context.setVariable(
        "showSellerDetails", visible(request.getShowSellerDetails()));
    context.setVariable("showShopName", visible(request.getShowShopName()));
    context.setVariable("showShopAddress", visible(request.getShowShopAddress()));
    context.setVariable("showShopTagline", visible(request.getShowShopTagline()));
    context.setVariable("showShopPhone", visible(request.getShowShopPhone()));
    context.setVariable("showShopEmail", visible(request.getShowShopEmail()));
    context.setVariable("showShopGstin", visible(request.getShowShopGstin()));
    context.setVariable("showShopPan", visible(request.getShowShopPan()));
    context.setVariable("showShopDlNo", visible(request.getShowShopDlNo()));
    context.setVariable("showShopFssai", visible(request.getShowShopFssai()));
    context.setVariable(
        "showBuyerDetails", visible(request.getShowBuyerDetails()));
    context.setVariable("showCustomerName", visible(request.getShowCustomerName()));
    context.setVariable("showCustomerAddress", visible(request.getShowCustomerAddress()));
    context.setVariable("showCustomerPhone", visible(request.getShowCustomerPhone()));
    context.setVariable("showCustomerEmail", visible(request.getShowCustomerEmail()));
    context.setVariable("showCustomerGstin", visible(request.getShowCustomerGstin()));
    context.setVariable("showCustomerPan", visible(request.getShowCustomerPan()));
    context.setVariable("showCustomerDlNo", visible(request.getShowCustomerDlNo()));
    context.setVariable(
        "showTaxDetails", request.getShowTaxDetails() == null || request.getShowTaxDetails());
    context.setVariable(
        "showPaymentMethod",
        request.getShowPaymentMethod() == null || request.getShowPaymentMethod());
    context.setVariable(
        "showAmountInWords",
        request.getShowAmountInWords() == null || request.getShowAmountInWords());
    context.setVariable("showHsn", request.getShowHsn() == null || request.getShowHsn());
    context.setVariable("showMfg", request.getShowMfg() == null || request.getShowMfg());
    context.setVariable("showBatch", request.getShowBatch() == null || request.getShowBatch());
    context.setVariable(
        "showSignatures", request.getShowSignatures() == null || request.getShowSignatures());
    context.setVariable("paymentMethod", request.getPaymentMethod());
    context.setVariable("reason", nullToEmpty(request.getReason()));

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

    context.setVariable("partyName", request.getPartyName());
    context.setVariable("partyAddress", request.getPartyAddress());
    context.setVariable("partyGstin", request.getPartyGstin());
    context.setVariable("partyPan", request.getPartyPan());
    context.setVariable("partyPhone", request.getPartyPhone());
    context.setVariable("partyEmail", request.getPartyEmail());
    context.setVariable("partyDlNo", request.getPartyDlNo());

    context.setVariable("items", request.getItems());

    BigDecimal taxableTotal = nz(request.getTaxableTotal());
    BigDecimal taxTotal = nz(request.getTaxTotal());
    BigDecimal grandTotal = nz(request.getGrandTotal());
    context.setVariable("taxableTotal", taxableTotal);
    context.setVariable("sgstAmount", nz(request.getSgstAmount()));
    context.setVariable("cgstAmount", nz(request.getCgstAmount()));
    context.setVariable(
        "sgstPercent",
        request.getSgstPercent() != null ? request.getSgstPercent() : BigDecimal.valueOf(2.5));
    context.setVariable(
        "cgstPercent",
        request.getCgstPercent() != null ? request.getCgstPercent() : BigDecimal.valueOf(2.5));
    context.setVariable("taxTotal", taxTotal);
    context.setVariable("roundOff", nz(request.getRoundOff()));
    context.setVariable("grandTotal", grandTotal);

    context.setVariable("amountInWords", request.getAmountInWords());
    context.setVariable("footerNote", request.getFooterNote());

    return context;
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }

  private static BigDecimal nz(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  private static boolean visible(Boolean flag) {
    return flag == null || flag;
  }
}
