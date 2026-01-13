package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.sale.RefundListResponse;
import com.inventory.product.rest.dto.sale.RefundRequest;
import com.inventory.product.rest.dto.sale.RefundResponse;
import com.inventory.product.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
public class RefundController {

  @Autowired
  private RefundService refundService;

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
}

