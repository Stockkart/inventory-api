package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.mis.MisExcelDocumentService;
import com.inventory.documentservice.service.mis.MisPdfDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
  private MisExcelDocumentService misExcelDocumentService;

  @Autowired
  private MisPdfDocumentService misPdfDocumentService;

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
