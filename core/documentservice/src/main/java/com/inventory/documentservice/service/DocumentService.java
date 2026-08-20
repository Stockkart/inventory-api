package com.inventory.documentservice.service;

import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.mis.MisExcelDocumentService;
import com.inventory.documentservice.service.mis.MisPdfDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Facade for document generation. Delegates to document-specific PDF services
 * and MIS exporters.
 */
@Service
@Slf4j
public class DocumentService {

  @Autowired
  private InvoicePdfService invoicePdfService;

  @Autowired
  private CreditNotePdfService creditNotePdfService;

  @Autowired
  private InvoiceDotMatrixRenderer dotMatrixRenderer;

  @Autowired
  private MisExcelDocumentService misExcelDocumentService;

  @Autowired
  private MisPdfDocumentService misPdfDocumentService;

  /**
   * Generate the invoice in the form its printer wants.
   *
   * <p>Every printer but the dot matrix gets a PDF. A dot matrix is not a page
   * device -- it draws a fixed grid of characters on continuous paper -- so it
   * gets that grid, with the control codes that set the pitch it is laid out
   * for. Sending it a page instead means measuring columns in millimetres and
   * hoping they land back on the character positions they were written as.
   *
   * @return a PDF, or ESC/P text when the printer is a dot matrix
   */
  public byte[] generateInvoice(GenerateInvoiceRequest request) {
    if (isDotMatrix(request)) {
      log.info("Rendering invoice {} for a dot-matrix printer", request.getInvoiceNo());
      return dotMatrixRenderer.render(request).getBytes(StandardCharsets.US_ASCII);
    }
    log.info("Generating invoice PDF for invoice: {}", request.getInvoiceNo());
    return invoicePdfService.generateInvoicePdf(request);
  }

  /**
   * The dot-matrix invoice without the printer's control codes, for reading on
   * a screen rather than sending to a printer.
   */
  public String generateInvoiceReadableText(GenerateInvoiceRequest request) {
    return dotMatrixRenderer.renderReadable(request);
  }

  /** Whether this invoice is bound for a dot-matrix printer. */
  public boolean isDotMatrix(GenerateInvoiceRequest request) {
    return request != null
        && PrinterType.DOT_MATRIX.name().equalsIgnoreCase(
            request.getPrinterType() != null ? request.getPrinterType().trim() : null);
  }

  /**
   * Render invoice HTML (same templates as PDF) for in-app preview without browser PDF chrome.
   */
  public String generateInvoiceHtml(GenerateInvoiceRequest request) {
    log.info("Generating invoice HTML preview for invoice: {}", request.getInvoiceNo());
    return invoicePdfService.renderInvoiceHtml(request);
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

  /** MIS tabular Excel (.xlsx). */
  public byte[] generateMisExcel(MisTabularDocumentRequest request) {
    log.info("Generating MIS Excel: {}", request != null ? request.getTitle() : null);
    return misExcelDocumentService.generateExcel(request);
  }

  /** MIS tabular PDF. */
  public byte[] generateMisPdf(MisTabularDocumentRequest request) {
    log.info("Generating MIS PDF: {}", request != null ? request.getTitle() : null);
    return misPdfDocumentService.generatePdf(request);
  }
}
