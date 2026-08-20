package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.documentservice.domain.PrinterType;
import com.inventory.product.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
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

    byte[] document = invoiceService.generateInvoicePdf(purchaseId, shopId, printerType);

    // A dot-matrix invoice is printer text, not a page. Labelling it a PDF
    // would hand the browser a file no reader can open.
    boolean asText = invoiceService.printsAsText(shopId, printerType);
    String fileName = "invoice_" + purchaseId + (asText ? ".txt" : ".pdf");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(asText ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", fileName);
    headers.setContentLength(document.length);

    return ResponseEntity.ok()
        .headers(headers)
        .body(document);
  }

  /**
   * The invoice as dot-matrix printer text.
   *
   * <p>The route the client has always called. It answered 404 because nothing
   * served it: the only invoice route was the PDF one, so a shop set to print on
   * a dot matrix could not print at all.
   *
   * <p>It returns characters and ESC/P control codes, not a page. Send the body
   * to the printer as it stands -- passing it through a page renderer would undo
   * the point of it, which is that the layout is already in the printer's own
   * grid.
   */
  @GetMapping("/{purchaseId}/dot-matrix")
  public ResponseEntity<byte[]> generateInvoiceDotMatrix(
      @PathVariable String purchaseId,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");
    log.info("Generating dot-matrix invoice for purchase: {}, shop: {}", purchaseId, shopId);

    byte[] text = invoiceService.generateInvoicePdf(
        purchaseId, shopId, PrinterType.DOT_MATRIX.name());

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.TEXT_PLAIN);
    headers.setContentDispositionFormData("attachment", "invoice_" + purchaseId + ".txt");
    headers.setContentLength(text.length);

    return ResponseEntity.ok()
        .headers(headers)
        .body(text);
  }
}

