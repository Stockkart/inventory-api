package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.response.VerticalSummaryResponse;
import com.inventory.product.service.vertical.VerticalSchemaService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequestMapping("/api/v1/verticals")
public class VerticalController {

  private final VerticalSchemaService verticalSchemaService;

  public VerticalController(VerticalSchemaService verticalSchemaService) {
    this.verticalSchemaService = verticalSchemaService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<VerticalSummaryResponse>>> listVerticals() {
    return ResponseEntity.ok(ApiResponse.success(verticalSchemaService.listActiveVerticals()));
  }
}
