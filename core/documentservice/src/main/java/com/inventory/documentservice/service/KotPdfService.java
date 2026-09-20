package com.inventory.documentservice.service;

import com.inventory.documentservice.domain.DocumentTemplateFamily;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.utils.constants.DocumentMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Kitchen Order Ticket rendering.
 *
 * <p>Mirrors {@link InvoicePdfService}, minus everything commercial: a KOT carries no price, tax,
 * total or GSTIN. The kitchen never sees money.
 *
 * <p>Dependencies are constructor-injected rather than field-injected so the service can be unit
 * tested without booting an application context.
 */
@Service
@Slf4j
public class KotPdfService {

  private final TemplateEngine templateEngine;
  private final HtmlToPdfConverter htmlToPdfConverter;
  private final MetricsWrapper metrics;

  public KotPdfService(
      TemplateEngine templateEngine,
      HtmlToPdfConverter htmlToPdfConverter,
      MetricsWrapper metrics) {
    this.templateEngine = templateEngine;
    this.htmlToPdfConverter = htmlToPdfConverter;
    this.metrics = metrics;
  }

  public byte[] generateKotPdf(GenerateKotRequest request) {
    try {
      byte[] pdf = htmlToPdfConverter.convert(renderKotHtml(request));
      metrics.record(
          DocumentMetricsConstants.GENERATED_TOTAL,
          1,
          "module",
          DocumentMetricsConstants.MODULE,
          "operation",
          "kot_pdf");
      return pdf;
    } catch (Exception e) {
      log.error("Error generating KOT PDF: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to generate KOT PDF", e);
    }
  }

  /** Kept public for preview and tests, matching InvoicePdfService. */
  public String renderKotHtml(GenerateKotRequest request) {
    Context context = new Context();
    context.setVariable("kotNo", request.getKotNo());
    context.setVariable("orderNo", request.getOrderNo());
    context.setVariable("orderType", request.getOrderType());
    context.setVariable("tableLabel", request.getTableLabel());
    context.setVariable("tokenNo", request.getTokenNo());
    context.setVariable("department", request.getDepartment());
    context.setVariable("roundNo", request.getRoundNo());
    context.setVariable("printedAt", request.getPrintedAt());
    context.setVariable("stewardName", request.getStewardName());
    context.setVariable("items", request.getItems() != null ? request.getItems() : List.of());
    KotStamp stamp = request.getStamp() != null ? request.getStamp() : KotStamp.NONE;
    // Two variables on purpose: the template branches on the stable name, prints the label.
    context.setVariable("stamp", stamp.name());
    context.setVariable("stampLabel", stamp.getLabel());
    context.setVariable("voidReason", request.getVoidReason());

    String template = DocumentTemplateFamily.KOT.templateFor(PrinterType.THERMAL_3INCH);
    log.debug("Rendering KOT {} for {} with stamp {}", request.getKotNo(), request.getDepartment(), stamp);
    return templateEngine.process(template, context);
  }
}
