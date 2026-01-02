package com.inventory.documentservice.rest.controller;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for document generation and management endpoints.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Slf4j
public class DocumentController {

  @Autowired
  private DocumentService documentService;

  /**
   * Generate invoice PDF.
   * 
   * @param request the invoice generation request containing purchase data
   * @param httpRequest HTTP request
   * @return PDF file as blob
   */
  @PostMapping("/invoice")
  public ResponseEntity<byte[]> generateInvoice(
      @RequestBody GenerateInvoiceRequest request,
      HttpServletRequest httpRequest) {
    
    log.info("Generating invoice PDF for invoice: {}", request.getInvoiceNo());
    
    byte[] pdfBytes = documentService.generateInvoice(request);
    
    String fileName = "invoice_" + (request.getInvoiceNo() != null ? request.getInvoiceNo() : "unknown") + ".pdf";
    
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", fileName);
    headers.setContentLength(pdfBytes.length);
    
    return ResponseEntity.ok()
        .headers(headers)
        .body(pdfBytes);
  }
}

