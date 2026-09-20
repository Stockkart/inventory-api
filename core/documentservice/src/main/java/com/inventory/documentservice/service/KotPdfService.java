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

  private static final float PROBE_MM = 600f;
  private static final float TAIL_MM = 8f;
  private static final float MIN_PAGE_MM = 40f;

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
      // Two passes: lay the ticket out on a page tall enough that nothing paginates, measure what
      // it actually needs, then render it on a page that size. A roll has no page, so a fixed
      // height either clips a long ticket or feeds half a metre of blank paper after a short one.
      float contentMm = htmlToPdfConverter.measureContentHeightMm(renderKotHtml(request, PROBE_MM));
      float pageMm = Math.min(PROBE_MM, Math.max(MIN_PAGE_MM, contentMm + TAIL_MM));
      byte[] pdf = htmlToPdfConverter.convert(renderKotHtml(request, pageMm));
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
    return renderKotHtml(request, PROBE_MM);
  }

  private String renderKotHtml(GenerateKotRequest request, float pageHeightMm) {
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
    context.setVariable("pageHeightMm", String.format(java.util.Locale.ROOT, "%.1f", pageHeightMm));

    String template = DocumentTemplateFamily.KOT.templateFor(PrinterType.THERMAL_3INCH);
    log.debug("Rendering KOT {} for {} with stamp {}", request.getKotNo(), request.getDepartment(), stamp);
    return templateEngine.process(template, context);
  }
}
