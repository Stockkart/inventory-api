package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.labels.LabelLayoutService;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.FieldCatalogResponse;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop-level barcode label layout endpoints (Req 1.8, 2.7, 4.1, 4.2).
 *
 * <p>Every endpoint resolves {@code userId}/{@code shopId} from the request attributes set by the
 * authentication interceptor and fails with {@code UNAUTHORIZED} before touching the service, same
 * pattern as {@link InvoiceSettingsController}. The request body never carries {@code shopId}.
 */
@RestController
@RequestMapping("/api/v1/shops/active-shop/barcode-label-layout")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class BarcodeLabelLayoutController {

  @Autowired
  private LabelLayoutService labelLayoutService;

  /** The shop's saved layout, or the Default_Layout with {@code isDefault=true} (Req 2.1, 2.2). */
  @GetMapping
  public ResponseEntity<ApiResponse<LabelLayoutResponse>> getLayout(
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(labelLayoutService.get(shopId, userId)));
  }

  /** Validates and upserts the shop's layout (Req 2.3, 2.7). */
  @PutMapping
  public ResponseEntity<ApiResponse<LabelLayoutResponse>> saveLayout(
      @RequestBody SaveLabelLayoutRequest request, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    log.info("Saving barcode label layout for shop: {}", shopId);
    return ResponseEntity.ok(
        ApiResponse.success(labelLayoutService.save(shopId, userId, request)));
  }

  /** The shop's Field_Catalog: printable fields, sticker sizes, shop type (Req 1.1, 1.8). */
  @GetMapping("/field-catalog")
  public ResponseEntity<ApiResponse<FieldCatalogResponse>> getFieldCatalog(
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(labelLayoutService.fieldCatalog(shopId, userId)));
  }

  /** Suggested layout for the shop's type; never persisted (Req 4.1, 4.2). */
  @GetMapping("/defaults")
  public ResponseEntity<ApiResponse<LabelLayoutResponse>> getDefaults(
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(labelLayoutService.shopTypeDefaults(shopId, userId)));
  }

  private static String requireUserId(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return userId;
  }

  private static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
    return shopId;
  }
}
