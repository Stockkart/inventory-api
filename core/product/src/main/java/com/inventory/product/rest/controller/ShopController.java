package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.RegisterShopRequest;
import com.inventory.product.rest.dto.request.ShopApprovalRequest;
import com.inventory.product.rest.dto.request.UpdateShopRequest;
import com.inventory.product.rest.dto.request.UpsertShopMenuRequest;
import com.inventory.product.rest.dto.response.SellCatalogResponse;
import com.inventory.product.rest.dto.response.ShopApprovalResponse;
import com.inventory.product.rest.dto.response.ShopDetailResponse;
import com.inventory.product.rest.dto.response.ShopMenuResponse;
import com.inventory.product.rest.dto.response.ShopRegistrationResponse;
import com.inventory.product.rest.dto.response.ShopSchemaResponse;
import com.inventory.product.service.ShopService;
import com.inventory.product.service.vertical.ShopCapabilityService;
import com.inventory.product.service.vertical.ShopSellCatalogService;
import com.inventory.product.service.vertical.ShopMenuService;
import com.inventory.product.service.vertical.VerticalSchemaService;
import com.inventory.pluginengine.capabilities.ShopUiCapabilities;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequestMapping("/api/v1/shops")
public class ShopController {

  @Autowired
  private ShopService shopService;

  @Autowired
  private VerticalSchemaService verticalSchemaService;

  @Autowired
  private ShopCapabilityService shopCapabilityService;

  @Autowired
  private ShopMenuService shopMenuService;

  @Autowired
  private ShopSellCatalogService shopSellCatalogService;

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<ShopRegistrationResponse>> register(@RequestBody RegisterShopRequest request,
                                                                        HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(ApiResponse.success(shopService.register(request, userId)));
  }

  @PostMapping("/admin/shops/{shopId}/approve")
  public ResponseEntity<ApiResponse<ShopApprovalResponse>> approve(@PathVariable String shopId,
                                                                   @RequestBody ShopApprovalRequest request) {
    return ResponseEntity.ok(ApiResponse.success(shopService.approve(shopId, request)));
  }

  @GetMapping("/active-shop")
  public ResponseEntity<ApiResponse<ShopDetailResponse>> getActiveShop(
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(shopService.getShopDetail(shopId, userId)));
  }

  @PatchMapping("/active-shop")
  public ResponseEntity<ApiResponse<ShopDetailResponse>> updateActiveShop(
      @RequestBody UpdateShopRequest request,
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(shopService.update(shopId, request, userId)));
  }

  /** Schema for the authenticated shop's vertical (server resolves verticalId from Shop). */
  @GetMapping("/me/schema")
  public ResponseEntity<ApiResponse<ShopSchemaResponse>> getShopSchema(
      @RequestParam(name = "mode", defaultValue = "regular") String mode,
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(verticalSchemaService.getShopSchema(shopId, userId, mode)));
  }

  /** UI capabilities for the authenticated shop (nav, sell surface, features). */
  @GetMapping("/me/capabilities")
  public ResponseEntity<ApiResponse<ShopUiCapabilities>> getShopCapabilities(
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(shopCapabilityService.getShopCapabilities(shopId, userId)));
  }

  /** Full menu document for menu-billing verticals (cafe, …). */
  @GetMapping("/me/menu")
  public ResponseEntity<ApiResponse<ShopMenuResponse>> getShopMenu(HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(shopMenuService.getShopMenu(shopId, userId)));
  }

  @PutMapping("/me/menu")
  public ResponseEntity<ApiResponse<ShopMenuResponse>> upsertShopMenu(
      @RequestBody UpsertShopMenuRequest request, HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(shopMenuService.upsertShopMenu(shopId, userId, request)));
  }

  /** Menu + direct-sell stock for the cashier sell surface (cafe). */
  @GetMapping("/me/sell-catalog")
  public ResponseEntity<ApiResponse<SellCatalogResponse>> getSellCatalog(
      @RequestParam(name = "q", required = false) String query,
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(shopSellCatalogService.getSellCatalog(shopId, userId, query)));
  }
}

