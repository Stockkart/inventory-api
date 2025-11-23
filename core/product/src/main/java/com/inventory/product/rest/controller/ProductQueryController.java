package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.product.ProductListResponse;
import com.inventory.product.rest.dto.product.ProductResponse;
import com.inventory.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductQueryController {

  @Autowired
  private ProductService productService;

  @GetMapping("/{barcode}")
  public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable String barcode) {
    return ResponseEntity.ok(ApiResponse.success(productService.getProduct(barcode)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<ProductListResponse>> list(@RequestParam(required = false, name = "q") String query) {
    return ResponseEntity.ok(ApiResponse.success(productService.searchProducts(query)));
  }
}

