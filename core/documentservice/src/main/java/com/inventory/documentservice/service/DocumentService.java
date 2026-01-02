package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for document generation and handling.
 * Handles PDF generation, document templates, and document management.
 */
@Service
@Slf4j
public class DocumentService {

  @Autowired
  private InvoicePdfService invoicePdfService;

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
}

