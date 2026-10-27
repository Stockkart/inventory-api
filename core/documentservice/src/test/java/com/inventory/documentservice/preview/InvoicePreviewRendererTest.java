package com.inventory.documentservice.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.inventory.documentservice.api.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.service.InvoiceTextRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

/** Every printer has exactly one preview renderer, and each renders its own printer's page. */
class InvoicePreviewRendererTest {

  private final TemplateEngine templateEngine = mock(TemplateEngine.class);

  private final List<InvoicePreviewRenderer> renderers =
      List.of(
          new DotMatrixInvoicePreviewRenderer(mock(InvoiceTextRenderer.class)),
          new NormalInvoicePreviewRenderer(templateEngine),
          new Thermal3InchInvoicePreviewRenderer(templateEngine));

  @Test
  void everyPrinterHasExactlyOneRenderer() {
    for (PrinterType printerType : PrinterType.values()) {
      long answering = renderers.stream().filter(r -> r.supports(printerType)).count();
      assertEquals(1, answering, printerType + " should have exactly one renderer");
    }
  }

  @Test
  void laserRendersTheA4Template() {
    new NormalInvoicePreviewRenderer(templateEngine).render(new GenerateInvoiceRequest());
    verify(templateEngine).process(eq("invoice/invoice"), any(IContext.class));
  }

  @Test
  void thermalRendersTheThreeInchTemplate() {
    new Thermal3InchInvoicePreviewRenderer(templateEngine).render(new GenerateInvoiceRequest());
    verify(templateEngine).process(eq("invoice/invoice-thermal-3inch"), any(IContext.class));
  }
}
