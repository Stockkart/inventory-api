package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.AttachBarcodeRequest;
import com.inventory.product.rest.dto.request.BarcodeLabelsRequest;
import com.inventory.product.rest.dto.request.GenerateBarcodesRequest;
import com.inventory.product.rest.dto.response.BarcodeLabelsResponse;
import com.inventory.product.rest.dto.response.BarcodePoolListResponse;
import com.inventory.product.rest.dto.response.GenerateBarcodesResponse;
import com.inventory.product.service.BarcodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/barcodes")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class BarcodeController {

  @Autowired
  private BarcodeService barcodeService;

  @PostMapping("/generate")
  public ResponseEntity<ApiResponse<GenerateBarcodesResponse>> generate(
      @RequestBody(required = false) GenerateBarcodesRequest request,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(barcodeService.generate(request, shopId)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<BarcodePoolListResponse>> list(
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(barcodeService.list(shopId, status, q, limit)));
  }

  @PostMapping("/{code}/attach")
  public ResponseEntity<ApiResponse<GenerateBarcodesResponse.BarcodePoolItemDto>> attach(
      @PathVariable String code,
      @RequestBody AttachBarcodeRequest request,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(barcodeService.attach(code, request, shopId)));
  }

  @PostMapping("/labels")
  public ResponseEntity<ApiResponse<BarcodeLabelsResponse>> labels(
      @RequestBody BarcodeLabelsRequest request,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(barcodeService.labels(request, shopId)));
  }

  private String requireShopId(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED, "Unauthorized access to shop barcodes");
    }
    return shopId;
  }
}
