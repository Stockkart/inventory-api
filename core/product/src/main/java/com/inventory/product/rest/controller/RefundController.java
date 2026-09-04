package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import org.springframework.util.StringUtils;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.RefundRequest;
import com.inventory.product.rest.dto.response.RefundListResponse;
import com.inventory.product.rest.dto.response.RefundResponse;
import com.inventory.product.service.CreditNoteService;
import com.inventory.product.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for refund operations.
 */
@RestController
@RequestMapping("/api/v1/refund")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class RefundController {

  @Autowired
  private RefundService refundService;

  @Autowired
  private CreditNoteService creditNoteService;

  /**
   * Process refund for a purchase.
   * Supports partial refunds by specifying items and quantities to refund.
   *
   * @param request refund request with purchaseId and items to refund
   * @param httpRequest HTTP request containing shopId and userId
   * @return refund response with calculated refund amount and refunded items
   */
  @PostMapping
  public ResponseEntity<ApiResponse<RefundResponse>> processRefund(
      @RequestBody RefundRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(refundService.processRefund(request, httpRequest)));
  }

  /**
   * Get list of refunds with pagination and search support.
   * Supports searching by invoice number, customer phone, customer ID, and customer email.
   *
   * @param page page number (1-based, optional, default: 1)
   * @param limit page size (optional, default: 20, max: 100)
   * @param invoiceNo optional invoice number to search
   * @param customerPhone optional customer phone to search
   * @param customerId optional customer ID to search
   * @param customerEmail optional customer email to search
   * @param httpRequest HTTP request containing shopId
   * @return list of refunds with pagination
   */
  @GetMapping
  public ResponseEntity<ApiResponse<RefundListResponse>> getRefunds(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String invoiceNo,
      @RequestParam(required = false) String customerPhone,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) String customerEmail,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        refundService.getRefunds(page, limit, invoiceNo, customerPhone, customerId, customerEmail, httpRequest)));
  }

  /**
   * Generate credit-note PDF for a customer sales return / refund.
   */
  @GetMapping("/{refundId}/pdf")
  public ResponseEntity<byte[]> generateCreditNotePdf(
      @PathVariable String refundId,
      @RequestParam(required = false) String printerType,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    log.info(
        "Generating customer credit note PDF for refund={}, shop={}, printerType={}",
        refundId,
        shopId,
        printerType);

    byte[] pdfBytes = creditNoteService.generateCustomerCreditNotePdf(refundId, shopId, printerType);
    String fileName = "credit_note_" + refundId + ".pdf";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", fileName);
    headers.setContentLength(pdfBytes.length);

    return ResponseEntity.ok().headers(headers).body(pdfBytes);
  }

  /**
   * Render the customer credit note as condensed plain text for the dot matrix print bridge.
   *
   * <p>Mirrors the invoice's own dot-matrix endpoint. The bridge speaks characters, not PDFs, so
   * a note printed through it has to arrive this way.
   */
  @GetMapping(value = "/{refundId}/dot-matrix", produces = "text/plain;charset=UTF-8")
  public ResponseEntity<String> generateCreditNoteDotMatrixText(
      @PathVariable String refundId, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    log.info("Generating dot matrix credit note text for refund={}, shop={}", refundId, shopId);

    String text = creditNoteService.generateCustomerCreditNoteText(refundId, shopId);

    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(text);
  }
}
