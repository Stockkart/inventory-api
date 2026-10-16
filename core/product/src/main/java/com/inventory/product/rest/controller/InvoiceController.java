package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for invoice generation endpoints.
 */
@RestController
@RequestMapping("/api/v1/invoices")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class InvoiceController {

  @Autowired
  private InvoiceService invoiceService;

  /**
   * Generate invoice PDF for a purchase.
   *
   * @param purchaseId the purchase ID
   * @param httpRequest HTTP request
   * @return PDF file as blob
   */
  @GetMapping("/{purchaseId}/pdf")
  public ResponseEntity<byte[]> generateInvoicePdf(
      @PathVariable String purchaseId,
      @RequestParam(required = false) String printerType,
      HttpServletRequest httpRequest) {

    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    log.info("Generating invoice PDF for purchase: {}, shop: {}, printerType: {}", purchaseId, shopId, printerType);

    byte[] pdfBytes = invoiceService.generateInvoicePdf(purchaseId, shopId, printerType);

    String fileName = "invoice_" + purchaseId + ".pdf";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", fileName);
    headers.setContentLength(pdfBytes.length);

    return ResponseEntity.ok()
        .headers(headers)
        .body(pdfBytes);
  }

  /**
   * Render the invoice as 80-column plain text for the dot matrix print bridge.
   *
   * @param purchaseId the purchase ID
   * @param httpRequest HTTP request
   * @return fixed-width plain text
   */
  @GetMapping(value = "/{purchaseId}/dot-matrix", produces = "text/plain;charset=UTF-8")
  public ResponseEntity<String> generateInvoiceDotMatrixText(
      @PathVariable String purchaseId, HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");

    log.info("Generating dot matrix invoice text for purchase: {}, shop: {}", purchaseId, shopId);

    String text = invoiceService.generateInvoiceText(purchaseId, shopId);

    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(text);
  }
}

