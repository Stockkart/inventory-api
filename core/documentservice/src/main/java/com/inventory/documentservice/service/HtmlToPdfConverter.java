package com.inventory.documentservice.service;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Shared HTML → PDF conversion (OpenHTMLToPDF). Document-specific services own Thymeleaf
 * rendering; this component owns the byte pipeline.
 */
@Component
public class HtmlToPdfConverter {

  /** CSS pixels are defined at 96dpi; PDF points at 72. */
  private static final float MM_PER_CSS_PX = 25.4f / 96f;

  public byte[] convert(String html) throws IOException {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.withHtmlContent(html, null);
      builder.toStream(outputStream);
      builder.run();
      return outputStream.toByteArray();
    }
  }

  /**
   * Lay the document out without producing a PDF, and report how tall its content actually is.
   *
   * <p>For a continuous roll there is no natural page height: a fixed one either clips a long
   * document or feeds blank paper after a short one. Measuring lets the caller size the page to
   * the content. Render the probe on a page tall enough that nothing paginates, or this measures
   * only the first page.
   */
  public float measureContentHeightMm(String html) {
    PdfRendererBuilder builder = new PdfRendererBuilder();
    builder.withHtmlContent(html, null);
    builder.toStream(ByteArrayOutputStream.nullOutputStream());
    try (PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
      renderer.layout();
      float dots = renderer.getRootBox().getHeight();
      float dotsPerPixel = renderer.getSharedContext().getDotsPerPixel();
      return (dots / dotsPerPixel) * MM_PER_CSS_PX;
    }
  }
}
