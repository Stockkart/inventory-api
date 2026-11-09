package com.inventory.documentservice.preview;

import com.inventory.documentservice.api.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;

/**
 * Renders the HTML shown as an invoice preview for one kind of printer.
 *
 * <p>Each printer has exactly one implementation, picked up by Spring: dot matrix previews the
 * text the printer receives, laser and thermal render their Thymeleaf templates. Supporting a new
 * printer means adding an implementation, not another branch in the service.
 */
public interface InvoicePreviewRenderer {

  /** Whether this renderer produces the preview for the given printer. */
  boolean supports(PrinterType printerType);

  /** The preview markup for this request. */
  String render(GenerateInvoiceRequest request);
}
