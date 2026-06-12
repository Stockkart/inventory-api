package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.response.VerticalSchemaResponse;
import com.inventory.product.rest.dto.response.VerticalSummaryResponse;
import com.inventory.product.service.vertical.VerticalSchemaService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequestMapping("/api/v1/verticals")
public class VerticalController {

  @Autowired private VerticalSchemaService verticalSchemaService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<VerticalSummaryResponse>>> listActive() {
    return ResponseEntity.ok(
        ApiResponse.success(verticalSchemaService.listActiveVerticals()));
  }

  /** Schema preview for onboarding (before a shop exists). */
  @GetMapping("/{verticalId}/schema")
  public ResponseEntity<ApiResponse<VerticalSchemaResponse>> getSchema(
      @PathVariable String verticalId,
      @RequestParam(name = "version", required = false) String version,
      @RequestParam(name = "mode", defaultValue = "regular") String mode) {
    return ResponseEntity.ok(
        ApiResponse.success(verticalSchemaService.getVerticalSchema(verticalId, version, mode)));
  }
}
