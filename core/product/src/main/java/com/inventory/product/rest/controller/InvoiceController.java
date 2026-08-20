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

import java.nio.charset.StandardCharsets;

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

    // A dot-matrix invoice is a printer file, not a page and not text: it
    // begins with control bytes, which anything trying to decode it will read
    // as characters. Labelling it a PDF would hand the browser a file no reader
    // can open; labelling it text hands Windows one Notepad will mangle.
    boolean forPrinter = invoiceService.printsAsText(shopId, printerType);
    String fileName = "invoice_" + purchaseId + (forPrinter ? ".prn" : ".pdf");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(
        forPrinter ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_PDF);
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
   *
   * <p>Served as a printer file rather than as text. It begins with control
   * bytes -- {@code 1B 40} to reset the printer, then the pitch -- and Windows
   * hands a .txt to Notepad, which reads those two bytes as a UTF-16 character
   * and every pair after them likewise, so the bill opens as a line of Chinese.
   * The bytes are not text and saying so stops anything trying to decode them.
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
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.setContentDispositionFormData("attachment", "invoice_" + purchaseId + ".prn");
    headers.setContentLength(text.length);

    return ResponseEntity.ok()
        .headers(headers)
        .body(text);
  }

  /**
   * The dot-matrix invoice as readable text, for looking at rather than
   * printing.
   *
   * <p>Shown in the browser instead of downloaded, and with the control codes
   * taken out: they are what fit the layout to the paper, but they are not
   * characters, and a viewer that treats them as characters shows the bill as
   * gibberish rather than as a bill.
   */
  @GetMapping(value = "/{purchaseId}/dot-matrix/preview", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> previewInvoiceDotMatrix(
      @PathVariable String purchaseId,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(invoiceService.previewDotMatrix(purchaseId, shopId));
  }
}

