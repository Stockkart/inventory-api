package com.inventory.documentservice.service.preview;

import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;

/**
 * Renders the HTML shown as an invoice preview for one kind of printer.
 *
 * <p>Implementations are picked up by Spring and consulted in {@link
 * org.springframework.core.annotation.Order} order, so the most specific renderer wins and the
 * template renderer answers for everything left over. Supporting a new printer means adding an
 * implementation, not another branch in the service.
 */
public interface InvoicePreviewRenderer {

  /** Whether this renderer produces the preview for the given printer. */
  boolean supports(PrinterType printerType);

  /** The preview markup for this request. */
  String render(GenerateInvoiceRequest request);
}
