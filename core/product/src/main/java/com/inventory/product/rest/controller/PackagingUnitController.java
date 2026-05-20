package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.response.PackagingUnitDto;
import com.inventory.product.service.PackagingUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequiredArgsConstructor
public class PackagingUnitController {

  private final PackagingUnitService packagingUnitService;

  /**
   * GST UQC packaging units with pharmacy sell rules (for registration dropdown).
   */
  @GetMapping("/packaging-units")
  public ResponseEntity<ApiResponse<List<PackagingUnitDto>>> listPackagingUnits() {
    return ResponseEntity.ok(ApiResponse.success(packagingUnitService.listAll()));
  }
}
