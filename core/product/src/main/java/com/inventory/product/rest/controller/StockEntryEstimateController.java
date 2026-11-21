package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.domain.model.enums.StockEntryEstimateState;
import com.inventory.product.rest.dto.request.UpsertStockEntryEstimateRequest;
import com.inventory.product.rest.dto.response.StockEntryEstimateListResponse;
import com.inventory.product.rest.dto.response.StockEntryEstimateResponse;
import com.inventory.product.service.StockEntryEstimateService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-entry-estimates")
@RequiredArgsConstructor
public class StockEntryEstimateController {

  private final StockEntryEstimateService stockEntryEstimateService;

  @GetMapping
  public ResponseEntity<ApiResponse<StockEntryEstimateListResponse>> list(
      @RequestParam(required = false) StockEntryEstimateState state,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(stockEntryEstimateService.list(shopId, state, page, size)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<StockEntryEstimateResponse>> create(
      @RequestBody UpsertStockEntryEstimateRequest request, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(stockEntryEstimateService.create(request, userId, shopId)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<StockEntryEstimateResponse>> get(
      @PathVariable String id, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(stockEntryEstimateService.get(id, shopId)));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<StockEntryEstimateResponse>> update(
      @PathVariable String id,
      @RequestBody UpsertStockEntryEstimateRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(stockEntryEstimateService.update(id, request, userId, shopId)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> discard(
      @PathVariable String id, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    stockEntryEstimateService.discard(id, shopId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/{id}/lock")
  public ResponseEntity<ApiResponse<StockEntryEstimateResponse>> lock(
      @PathVariable String id, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(stockEntryEstimateService.lock(id, userId, shopId)));
  }

  @PostMapping("/{id}/mark-converted")
  public ResponseEntity<ApiResponse<StockEntryEstimateResponse>> markConverted(
      @PathVariable String id,
      @RequestBody Map<String, String> body,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String invoiceId = body != null ? body.get("vendorPurchaseInvoiceId") : null;
    return ResponseEntity.ok(
        ApiResponse.success(stockEntryEstimateService.markConverted(id, invoiceId, shopId)));
  }
}
