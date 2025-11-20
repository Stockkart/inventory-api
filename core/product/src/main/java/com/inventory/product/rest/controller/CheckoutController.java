package com.inventory.product.rest.controller;

import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(checkoutService.checkout(request));
    }

    @PostMapping("/sales/{saleId}/invalidate")
    public ResponseEntity<SaleStatusResponse> invalidate(@PathVariable String saleId,
                                                         @RequestBody InvalidateSaleRequest request) {
        return ResponseEntity.ok(checkoutService.invalidate(saleId, request));
    }
}

