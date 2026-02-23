package com.inventory.pricing.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.rest.dto.BulkUpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.PricingResponse;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceRequest;
import com.inventory.pricing.service.PricingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pricing")
@Slf4j
public class PricingController {

  @Autowired
  private PricingService pricingService;

  /**
   * Update default/selling price for a single pricing record by ID.
   * Allowed fields: maximumRetailPrice, priceToRetail, rates, defaultRate.
   */
  @PatchMapping("/{pricingId}")
  public ResponseEntity<ApiResponse<PricingResponse>> updateDefaultPrice(
      @PathVariable String pricingId,
      @RequestBody UpdateDefaultPriceRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
    return ResponseEntity.ok(ApiResponse.success(pricingService.updateDefaultPrice(pricingId, request, shopId)));
  }

  /**
   * Bulk update default/selling price for multiple pricing records.
   */
  @PostMapping("/bulk-update")
  public ResponseEntity<ApiResponse<List<PricingResponse>>> bulkUpdateDefaultPrice(
      @RequestBody BulkUpdateDefaultPriceRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
    if (request == null || request.getUpdates() == null || request.getUpdates().isEmpty()) {
      throw new ValidationException("Updates list cannot be null or empty");
    }
    List<PricingResponse> results = pricingService.bulkUpdateDefaultPrice(request.getUpdates(), shopId);
    return ResponseEntity.ok(ApiResponse.success(results));
  }
}
