package com.inventory.product.rest.controller;

import com.inventory.product.rest.dto.product.ProductListResponse;
import com.inventory.product.rest.dto.product.ProductResponse;
import com.inventory.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductQueryController {

    private final ProductService productService;

    @GetMapping("/{barcode}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable String barcode) {
        return ResponseEntity.ok(productService.getProduct(barcode));
    }

    @GetMapping
    public ResponseEntity<ProductListResponse> list(@RequestParam(required = false, name = "q") String query) {
        return ResponseEntity.ok(productService.searchProducts(query));
    }
}

