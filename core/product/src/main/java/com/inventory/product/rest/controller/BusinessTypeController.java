package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.CreateBusinessTypeRequest;
import com.inventory.product.rest.dto.response.BusinessTypeResponse;
import com.inventory.product.service.BusinessTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-types")
@Latency(module = "product")
@RecordStatusCodes(module = "product")
public class BusinessTypeController {

  @Autowired
  private BusinessTypeService businessTypeService;

  @PostMapping
  public ResponseEntity<ApiResponse<BusinessTypeResponse>> create(@RequestBody CreateBusinessTypeRequest request) {
    return ResponseEntity.ok(ApiResponse.success(businessTypeService.create(request)));
  }
}

