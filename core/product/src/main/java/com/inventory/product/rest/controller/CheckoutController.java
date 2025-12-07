package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.AddToCartResponse;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.PurchaseListResponse;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.rest.dto.sale.UpdatePurchaseStatusRequest;
import com.inventory.product.service.CheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}

