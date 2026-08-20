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
   * The invoice for a dot-matrix printer.
   *
   * <p>The ESC/P stream, to be sent to the printer's port rather than opened.
   * The bill is 137 characters wide, which is 13.7 inches at the pitch a driver
   * prints at and 8 inches in condensed -- and condensed is set by a control
   * code, so the full layout only fits the paper when the codes reach the
   * printer. They only do that going to the port: through a driver they are not
   * commands but characters, which is how a bill comes out as gibberish.
   *
   * <pre>copy /b invoice_&lt;id&gt;.prn PRN</pre>
   *
   * <p>{@code ?raw=false} returns the same layout as plain characters, for
   * reading. It will not fit an 80-column printer, since nothing in it can ask
   * for condensed.
   */
  @GetMapping("/{purchaseId}/dot-matrix")
  public ResponseEntity<byte[]> generateInvoiceDotMatrix(
      @PathVariable String purchaseId,
      @RequestParam(required = false, defaultValue = "true") boolean raw,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");
    log.info("Generating dot-matrix invoice for purchase: {}, shop: {}, raw: {}",
        purchaseId, shopId, raw);

    byte[] body = raw
        ? invoiceService.generateInvoicePdf(purchaseId, shopId, PrinterType.DOT_MATRIX.name())
        : invoiceService.previewDotMatrix(purchaseId, shopId)
            .getBytes(StandardCharsets.US_ASCII);

    HttpHeaders headers = new HttpHeaders();
    // The charset is stated rather than left to be guessed. These are single
    // bytes, and a reader that decides otherwise reads them two at a time.
    headers.setContentType(raw
        ? MediaType.APPLICATION_OCTET_STREAM
        : new MediaType("text", "plain", StandardCharsets.US_ASCII));
    headers.setContentDispositionFormData(
        "attachment", "invoice_" + purchaseId + (raw ? ".prn" : ".txt"));
    headers.setContentLength(body.length);

    return ResponseEntity.ok()
        .headers(headers)
        .body(body);
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

