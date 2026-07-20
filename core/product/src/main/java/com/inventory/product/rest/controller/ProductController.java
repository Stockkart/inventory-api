package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.response.InventoryDetailResponse;
import com.inventory.product.rest.dto.response.ProductSuggestionDto;
import com.inventory.product.service.InventoryService;
import com.inventory.product.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class ProductController {

  @Autowired
  private ProductService productService;

  @Autowired
  private InventoryService inventoryService;

  /** Typeahead for registration: suggest existing catalog products for this shop. */
  @GetMapping("/suggest")
  public ResponseEntity<ApiResponse<List<ProductSuggestionDto>>> suggest(
      @RequestParam("q") String q,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(productService.suggest(shopId, q)));
  }

  /** Full catalog identity for a selected product (prefill source). */
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ProductSuggestionDto>> getById(
      @PathVariable String id,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(productService.getById(shopId, id)));
  }

  /**
   * Most recent inventory lot for a catalog product. Returns {@code null} when the product has no
   * prior stock-ins (identity-only prefill on the client).
   */
  @GetMapping("/{id}/last-inventory")
  public ResponseEntity<ApiResponse<InventoryDetailResponse>> getLastInventory(
      @PathVariable String id,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    productService.getById(shopId, id);
    return ResponseEntity.ok(
        ApiResponse.success(
            inventoryService.getLastInventoryByProductId(shopId, id).orElse(null)));
  }

  private String requireShopId(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop products");
    }
    return shopId;
  }
}
