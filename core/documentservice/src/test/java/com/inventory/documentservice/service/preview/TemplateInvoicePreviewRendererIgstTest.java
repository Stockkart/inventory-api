package com.inventory.documentservice.service.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * The invoice templates choose between the CGST/SGST pair and the IGST column on {@code
 * igstAmount}: with the variable unset the expression reads null and an interstate bill quietly
 * prints as CGST/SGST.
 *
 * <p>The variable is set here, in the renderer, while the field it reads lives on the request.
 * Those two arrived on separate branches - the renderer with dot matrix printing, the field with
 * interstate GST - so a merge that keeps only one side still compiles.
 */
class TemplateInvoicePreviewRendererIgstTest {

  private final TemplateInvoicePreviewRenderer renderer =
      new TemplateInvoicePreviewRenderer(new TemplateEngine());

  @Test
  void interstateTaxReachesTheTemplate() {
    GenerateInvoiceRequest request = new GenerateInvoiceRequest();
    request.setIgstAmount(new BigDecimal("18.00"));
    request.setIgstPercent(new BigDecimal("18.00"));

    Context context = renderer.prepareTemplateContext(request);

    assertEquals(new BigDecimal("18.00"), context.getVariable("igstAmount"));
    assertEquals(new BigDecimal("18.00"), context.getVariable("igstPercent"));
  }

  @Test
  void anInvoiceWithoutInterstateTaxSaysZeroRatherThanNothing() {
    Context context = renderer.prepareTemplateContext(new GenerateInvoiceRequest());

    assertEquals(BigDecimal.ZERO, context.getVariable("igstAmount"));
    assertEquals(BigDecimal.ZERO, context.getVariable("igstPercent"));
  }
}
