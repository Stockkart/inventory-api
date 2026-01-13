package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.AddToCartResponse;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.PurchaseListResponse;
import com.inventory.product.rest.dto.sale.UpdatePurchaseStatusRequest;
import com.inventory.product.service.CheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CheckoutController {

  @Autowired
  private CheckoutService checkoutService;

  @GetMapping("/cart")
  public ResponseEntity<ApiResponse<AddToCartResponse>> getCart(HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.getCart(httpRequest)));
  }

  @PostMapping("/cart/upsert")
  public ResponseEntity<ApiResponse<AddToCartResponse>> addToCart(@RequestBody AddToCartRequest request,
                                                                  HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.addToCart(request, httpRequest)));
  }

  @PutMapping("/cart/status")
  public ResponseEntity<ApiResponse<CheckoutResponse>> updatePurchaseStatus(@RequestBody UpdatePurchaseStatusRequest request,
                                                                            HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.updatePurchaseStatus(request, httpRequest)));
  }

  @GetMapping("/purchases")
  public ResponseEntity<ApiResponse<PurchaseListResponse>> getPurchases(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String order,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.getPurchases(page, limit, order, httpRequest)));
  }

  /**
   * Search purchases with pagination and customer search support.
   * Supports searching by invoice number (regex), customer email, phone, and name (regex).
   *
   * @param page page number (1-based, optional, default: 1)
   * @param limit page size (optional, default: 20, max: 100)
   * @param invoiceNo optional invoice number regex pattern to search
   * @param customerEmail optional customer email to search
   * @param customerPhone optional customer phone to search
   * @param customerName optional customer name regex pattern to search
   * @param httpRequest HTTP request containing shopId
   * @return list of purchases with pagination
   */
  @GetMapping("/purchases/search")
  public ResponseEntity<ApiResponse<PurchaseListResponse>> searchPurchases(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String invoiceNo,
      @RequestParam(required = false) String customerEmail,
      @RequestParam(required = false) String customerPhone,
      @RequestParam(required = false) String customerName,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        checkoutService.searchPurchases(page, limit, invoiceNo, customerEmail, customerPhone, customerName, httpRequest)));
  }
}

