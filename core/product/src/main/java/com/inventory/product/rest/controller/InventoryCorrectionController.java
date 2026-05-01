package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.product.rest.dto.request.CreateInventoryCorrectionRequest;
import com.inventory.product.rest.dto.request.ProcessInventoryCorrectionLineRequest;
import com.inventory.product.rest.dto.response.InventoryCorrectionDto;
import com.inventory.product.rest.dto.response.InventoryCorrectionListResponse;
import com.inventory.product.service.InventoryCorrectionService;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/inventory-corrections")
public class InventoryCorrectionController {

  @Autowired private InventoryCorrectionService inventoryCorrectionService;

  @PostMapping
  public ResponseEntity<ApiResponse<InventoryCorrectionDto>> create(
      @RequestBody CreateInventoryCorrectionRequest request, HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    ensureAuth(userId, shopId);
    return ResponseEntity.ok(
        ApiResponse.success(inventoryCorrectionService.createPending(request, shopId, userId)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<InventoryCorrectionListResponse>> list(
      HttpServletRequest httpRequest,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
    return ResponseEntity.ok(
        ApiResponse.success(inventoryCorrectionService.list(shopId, status, page, size)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<InventoryCorrectionDto>> getById(
      @PathVariable String id, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
    return ResponseEntity.ok(ApiResponse.success(inventoryCorrectionService.getById(id, shopId)));
  }

  @PostMapping("/{id}/lines/{lineId}/approve")
  public ResponseEntity<ApiResponse<InventoryCorrectionDto>> approveLine(
      @PathVariable String id, @PathVariable String lineId, HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    ensureAuth(userId, shopId);
    return ResponseEntity.ok(
        ApiResponse.success(inventoryCorrectionService.approveLine(id, lineId, shopId, userId)));
  }

  @PostMapping("/{id}/lines/{lineId}/reject")
  public ResponseEntity<ApiResponse<InventoryCorrectionDto>> rejectLine(
      @PathVariable String id,
      @PathVariable String lineId,
      @RequestBody(required = false) ProcessInventoryCorrectionLineRequest request,
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    ensureAuth(userId, shopId);
    String reason = request != null ? request.getReason() : null;
    return ResponseEntity.ok(
        ApiResponse.success(
            inventoryCorrectionService.rejectLine(id, lineId, reason, shopId, userId)));
  }

  private void ensureAuth(String userId, String shopId) {
    if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
  }
}

