package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.service.InventoryService;
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
@RequestMapping("/api/v1/inventory")
public class InventoryController {

  @Autowired
  private InventoryService inventoryService;

  @PostMapping
  public ResponseEntity<ApiResponse<InventoryReceiptResponse>> create(
      @RequestBody CreateInventoryRequest request,
      HttpServletRequest httpRequest) {
    // Get userId and shopId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.create(request, userId, shopId)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<InventoryListResponse>> list(
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes to ensure user can only access their shop's inventory
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop inventory");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.list(shopId)));
  }

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<InventoryListResponse>> search(
      @RequestParam("q") String q,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes to ensure user can only search their shop's inventory
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop inventory");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.search(shopId, q)));
  }

  @GetMapping("/{lotId}")
  public ResponseEntity<ApiResponse<InventoryDetailResponse>> getLot(@PathVariable String lotId) {
    return ResponseEntity.ok(ApiResponse.success(inventoryService.getLot(lotId)));
  }
}
