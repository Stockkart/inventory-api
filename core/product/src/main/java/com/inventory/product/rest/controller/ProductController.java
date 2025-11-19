package com.inventory.product.rest.controller;

import com.inventory.product.rest.dto.ProductListDto;
import com.inventory.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @GetMapping("/get-plugin")
    public ResponseEntity<String> getCurrentPlugin(){
        return ResponseEntity.ok(productService.getCurrentPlugin());
    }

    @GetMapping("/")
    public ResponseEntity<ProductListDto> getProducts(){
        return ResponseEntity.ok(productService.getProducts());
    }

}
