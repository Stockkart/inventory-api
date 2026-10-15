package com.inventory.documentservice.service.preview;

import com.inventory.documentservice.domain.PrinterType;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;

/**
 * Previews an invoice for a 3-inch thermal receipt printer, from
 * {@code invoice/invoice-thermal-3inch.html}.
 */
@Component
public class Thermal3InchInvoicePreviewRenderer extends AbstractTemplateInvoicePreviewRenderer {

  public Thermal3InchInvoicePreviewRenderer(TemplateEngine templateEngine) {
    super(templateEngine);
  }

  @Override
  protected PrinterType printerType() {
    return PrinterType.THERMAL_3INCH;
  }
}
