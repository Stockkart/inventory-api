package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.VendorPurchaseReturnRequest;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnListResponse;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnResponse;
import com.inventory.product.service.CreditNoteService;
import com.inventory.product.service.VendorPurchaseReturnService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vendor-purchase-returns")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class VendorPurchaseReturnController {

  @Autowired
  private VendorPurchaseReturnService vendorPurchaseReturnService;

  @Autowired
  private CreditNoteService creditNoteService;

  /**
   * List supplier purchase returns for the shop (pagination, newest first).
   *
   * @param page page number (1-based, default 1)
   * @param limit page size (default 20, max 100)
   * @param invoiceNo optional exact vendor purchase invoice number
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<VendorPurchaseReturnListResponse>> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String invoiceNo,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            vendorPurchaseReturnService.listReturns(page, limit, invoiceNo, httpRequest)));
  }

  /**
   * Record a stock return against a vendor purchase invoice (GSTR-2 CDNR/CDNUR).
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<VendorPurchaseReturnResponse>> create(
      @RequestBody VendorPurchaseReturnRequest body, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    return ResponseEntity.ok(ApiResponse.success(vendorPurchaseReturnService.processReturn(body, httpRequest)));
  }

  /**
   * Generate credit-note PDF for a vendor purchase return.
   */
  @GetMapping(value = "/{returnId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> generateCreditNotePdf(
      @PathVariable String returnId,
      @RequestParam(required = false) String printerType,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    log.info(
        "Generating vendor debit note PDF for return={}, shop={}, printerType={}",
        returnId,
        shopId,
        printerType);

    byte[] pdfBytes = creditNoteService.generateVendorCreditNotePdf(returnId, shopId, printerType);
    String fileName = "debit_note_" + returnId + ".pdf";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", fileName);
    headers.setContentLength(pdfBytes.length);

    return ResponseEntity.ok().headers(headers).body(pdfBytes);
  }

  /**
   * Render the vendor debit note as condensed plain text for the dot matrix print bridge.
   *
   * <p>Same document as the customer credit note, titled for the other side of the counter - see
   * CreditNoteService.
   */
  @GetMapping(value = "/{returnId}/dot-matrix", produces = "text/plain;charset=UTF-8")
  public ResponseEntity<String> generateDebitNoteDotMatrixText(
      @PathVariable String returnId, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    log.info("Generating dot matrix debit note text for return={}, shop={}", returnId, shopId);

    String text = creditNoteService.generateVendorCreditNoteText(returnId, shopId);

    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(text);
  }
}
