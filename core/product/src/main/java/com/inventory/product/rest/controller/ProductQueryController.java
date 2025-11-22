package com.inventory.product.rest.controller;

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
  public ResponseEntity<ProductResponse> getProduct(@PathVariable String barcode) {
    return ResponseEntity.ok(productService.getProduct(barcode));
  }

  @GetMapping
  public ResponseEntity<ProductListResponse> list(@RequestParam(required = false, name = "q") String query) {
    return ResponseEntity.ok(productService.searchProducts(query));
  }
}

