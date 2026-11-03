package com.inventory.documentservice.preview;

import com.inventory.documentservice.api.PrinterType;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;

/** Previews an invoice for an A4 laser or inkjet printer, from {@code invoice/invoice.html}. */
@Component
public class NormalInvoicePreviewRenderer extends AbstractTemplateInvoicePreviewRenderer {

  public NormalInvoicePreviewRenderer(TemplateEngine templateEngine) {
    super(templateEngine);
  }

  @Override
  protected PrinterType printerType() {
    return PrinterType.NORMAL;
  }
}
