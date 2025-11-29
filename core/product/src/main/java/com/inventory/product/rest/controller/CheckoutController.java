package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
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

  @PostMapping("/checkout")
  public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(@RequestBody CheckoutRequest request,
                                                                 HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.checkout(request, httpRequest)));
  }

  @PostMapping("/sales/{saleId}/invalidate")
  public ResponseEntity<ApiResponse<SaleStatusResponse>> invalidate(@PathVariable String saleId,
                                                       @RequestBody InvalidateSaleRequest request) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.invalidate(saleId, request)));
  }
}

