package com.inventory.product.rest.controller;

import com.inventory.product.rest.dto.business.BusinessTypeResponse;
import com.inventory.product.rest.dto.business.CreateBusinessTypeRequest;
import com.inventory.product.service.BusinessTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-types")
@RequiredArgsConstructor
public class BusinessTypeController {

    private final BusinessTypeService businessTypeService;

    @PostMapping
    public ResponseEntity<BusinessTypeResponse> create(@RequestBody CreateBusinessTypeRequest request) {
        return ResponseEntity.ok(businessTypeService.create(request));
    }
}

