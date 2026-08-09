package com.inventory.documentservice.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Shared HTML → PDF conversion (OpenHTMLToPDF). Document-specific services
 * own Thymeleaf rendering; this component owns the byte pipeline.
 */
@Component
public class HtmlToPdfConverter {

  public byte[] convert(String html) throws IOException {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.withHtmlContent(html, null);
      builder.toStream(outputStream);
      builder.run();
      return outputStream.toByteArray();
    }
  }
}
