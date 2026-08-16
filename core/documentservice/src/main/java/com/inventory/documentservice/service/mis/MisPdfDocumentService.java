package com.inventory.documentservice.service.mis;

import com.inventory.documentservice.rest.dto.mis.MisDocumentKpi;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.utils.constants.DocumentMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Builds MIS report PDFs via Thymeleaf + OpenHTMLToPDF. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MisPdfDocumentService {

  private final TemplateEngine templateEngine;
  private final MetricsWrapper metrics;

  public byte[] generatePdf(MisTabularDocumentRequest request) {
    try {
      List<MisDocumentKpi> kpis =
          request.getKpis() != null ? request.getKpis() : List.<MisDocumentKpi>of();
      List<String> columns =
          request.getColumns() != null ? request.getColumns() : List.<String>of();
      List<String> secondaryColumns =
          request.getSecondaryColumns() != null
              ? request.getSecondaryColumns()
              : List.<String>of();

      Context context = new Context();
      context.setVariable("title", nullToEmpty(request.getTitle()));
      context.setVariable("shopName", nullToEmpty(request.getShopName()));
      context.setVariable("periodLabel", nullToEmpty(request.getPeriodLabel()));
      context.setVariable("generatedAtLabel", nullToEmpty(request.getGeneratedAtLabel()));
      context.setVariable("kpis", kpis);
      context.setVariable("kpiChunks", MisDocumentColumnStyle.chunk(kpis, 4));
      context.setVariable("columns", columns);
      context.setVariable("numericFlags", MisDocumentColumnStyle.flags(columns, true));
      context.setVariable("dateFlags", MisDocumentColumnStyle.flags(columns, false));
      context.setVariable(
          "rows", request.getRows() != null ? request.getRows() : List.<List<String>>of());
      context.setVariable(
          "hasSecondary",
          !CollectionUtils.isEmpty(request.getSecondaryColumns())
              && !CollectionUtils.isEmpty(request.getSecondaryRows()));
      context.setVariable("secondarySheetTitle", nullToEmpty(request.getSecondarySheetTitle()));
      context.setVariable("secondaryColumns", secondaryColumns);
      context.setVariable(
          "secondaryNumericFlags", MisDocumentColumnStyle.flags(secondaryColumns, true));
      context.setVariable(
          "secondaryRows",
          request.getSecondaryRows() != null
              ? request.getSecondaryRows()
              : List.<List<String>>of());

      String html = templateEngine.process("mis/tabular-report", context);
      return htmlToPdf(html);
    } catch (Exception e) {
      log.error("Failed to build MIS PDF: {}", e.getMessage(), e);
      throw new IllegalStateException("Failed to generate MIS PDF", e);
    }
  }

  private byte[] htmlToPdf(String html) throws Exception {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.useFastMode();
      builder.withHtmlContent(html, null);
      builder.toStream(out);
      builder.run();
      metrics.record(
          DocumentMetricsConstants.GENERATED_TOTAL,
          1,
          "module",
          DocumentMetricsConstants.MODULE,
          "operation",
          "mis_pdf");
      return out.toByteArray();
    }
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }
}
