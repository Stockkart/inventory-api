package com.inventory.documentservice.service;

import com.inventory.documentservice.api.DotMatrixDocument;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Facade for invoice and credit-note generation. Delegates to the document-specific renderers.
 * MIS reports are rendered through {@link com.inventory.documentservice.api.MisDocuments}.
 */
@Service
@Slf4j
public class DocumentService {

  @Autowired
  private InvoicePdfService invoicePdfService;

  @Autowired
  private InvoiceTextRenderer invoiceTextRenderer;

  @Autowired
  private CreditNotePdfService creditNotePdfService;

  /**
   * Generate invoice PDF.
   *
   * @param request the invoice generation request
   * @return PDF as byte array
   */
  public byte[] generateInvoice(GenerateInvoiceRequest request) {
    log.info("Generating invoice PDF for invoice: {}", request.getInvoiceNo());
    return invoicePdfService.generateInvoicePdf(request);
  }

  /**
   * Render invoice HTML (same templates as PDF) for in-app preview without browser PDF chrome.
   */
  public String generateInvoiceHtml(GenerateInvoiceRequest request) {
    log.info("Generating invoice HTML preview for invoice: {}", request.getInvoiceNo());
    return invoicePdfService.renderInvoiceHtml(request);
  }

  /**
   * Render the invoice as fixed-width plain text for dot matrix printers.
   *
   * @param request the invoice generation request
   * @return 80-column plain text, newline separated, with no control characters
   */
  public String generateInvoiceText(GenerateInvoiceRequest request) {
    log.info("Generating invoice text for invoice: {}", request.getInvoiceNo());
    return invoiceTextRenderer.render(request);
  }

  /**
   * Render the invoice for the print bridge: its text, whether it is an invoice or an estimate,
   * and its number.
   */
  public DotMatrixDocument renderInvoiceForPrint(GenerateInvoiceRequest request) {
    return new DotMatrixDocument(
        generateInvoiceText(request), InvoiceTextRenderer.kindOf(request), request.getInvoiceNo());
  }

  /**
   * Render a credit or debit note for the print bridge: its text, which of the two it is, and
   * its number.
   */
  public DotMatrixDocument renderCreditNoteForPrint(GenerateCreditNoteRequest request) {
    return new DotMatrixDocument(
        generateCreditNoteText(request),
        InvoiceTextRenderer.kindOf(request),
        request.getCreditNoteNo());
  }

  /**
   * Render a credit or debit note as fixed-width plain text for dot matrix printers.
   *
   * <p>Which of the two it is comes from the note's own party role, so there is one method here
   * rather than two - see {@link InvoiceTextRenderer#render(GenerateCreditNoteRequest)}.
   *
   * @param request the note generation request
   * @return condensed fixed-width plain text, newline separated, with no page control
   */
  public String generateCreditNoteText(GenerateCreditNoteRequest request) {
    log.info(
        "Generating credit note text for note: {}",
        request != null ? request.getCreditNoteNo() : null);
    return invoiceTextRenderer.render(request);
  }

  /** Generate credit-note PDF (customer or vendor). */
  public byte[] generateCreditNote(GenerateCreditNoteRequest request) {
    log.info(
        "Generating credit note PDF for note: {}",
        request != null ? request.getCreditNoteNo() : null);
    return creditNotePdfService.generateCreditNotePdf(request);
  }

  /** Render credit-note HTML (same templates as PDF) for preview. */
  public String generateCreditNoteHtml(GenerateCreditNoteRequest request) {
    log.info(
        "Generating credit note HTML preview for note: {}",
        request != null ? request.getCreditNoteNo() : null);
    return creditNotePdfService.renderCreditNoteHtml(request);
  }
}
