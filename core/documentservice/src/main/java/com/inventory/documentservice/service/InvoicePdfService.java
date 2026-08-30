package com.inventory.documentservice.service;

import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.service.preview.InvoicePreviewRenderer;
import com.inventory.documentservice.utils.constants.DocumentMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for generating invoice PDFs using Thymeleaf templates and OpenHTMLToPDF.
 */
@Service
@Slf4j
public class InvoicePdfService {

  @Autowired
  private HtmlToPdfConverter htmlToPdfConverter;

  @Autowired
  private MetricsWrapper metrics;

  @Autowired
  private List<InvoicePreviewRenderer> previewRenderers;

  /**
   * Generate invoice PDF from purchase data using Thymeleaf template.
   *
   * @param request the invoice generation request containing all invoice data
   * @return PDF as byte array
   */
  public byte[] generateInvoicePdf(GenerateInvoiceRequest request) {
    try {
      log.debug("Generating invoice PDF for invoice: {}", request.getInvoiceNo());
      String html = renderInvoiceHtml(request);
      byte[] pdf = htmlToPdfConverter.convert(html);
      metrics.record(
          DocumentMetricsConstants.GENERATED_TOTAL,
          1,
          "module",
          DocumentMetricsConstants.MODULE,
          "operation",
          "invoice_pdf");
      return pdf;
    } catch (Exception e) {
      log.error("Error generating invoice PDF: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to generate invoice PDF", e);
    }
  }

  /**
   * Render the markup previewed for this invoice, as the request's printer describes it.
   *
   * <p>The renderer is chosen by asking each one, in order, whether it serves this printer. The
   * list is ordered, so a printer-specific renderer answers before the template renderer that
   * accepts everything, and a new kind of printer arrives as a new InvoicePreviewRenderer rather
   * than another branch here.
   */
  public String renderInvoiceHtml(GenerateInvoiceRequest request) {
    PrinterType printerType = PrinterType.from(request.getPrinterType());
    return previewRenderers.stream()
        .filter(renderer -> renderer.supports(printerType))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No preview renderer for " + printerType))
        .render(request);
  }

}
