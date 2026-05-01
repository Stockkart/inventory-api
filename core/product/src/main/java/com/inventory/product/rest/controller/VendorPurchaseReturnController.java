package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.VendorPurchaseReturnRequest;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnListResponse;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnResponse;
import com.inventory.product.service.VendorPurchaseReturnService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vendor-purchase-returns")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class VendorPurchaseReturnController {

  @Autowired
  private VendorPurchaseReturnService vendorPurchaseReturnService;

  /**
   * List supplier purchase returns for the shop (pagination, newest first).
   *
   * @param page page number (1-based, default 1)
   * @param limit page size (default 20, max 100)
   * @param invoiceNo optional exact vendor purchase invoice number
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<VendorPurchaseReturnListResponse>> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String invoiceNo,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            vendorPurchaseReturnService.listReturns(page, limit, invoiceNo, httpRequest)));
  }

  /**
   * Record a stock return against a vendor purchase invoice (GSTR-2 CDNR/CDNUR).
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<VendorPurchaseReturnResponse>> create(
      @RequestBody VendorPurchaseReturnRequest body, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context required");
    }
    return ResponseEntity.ok(ApiResponse.success(vendorPurchaseReturnService.processReturn(body, httpRequest)));
  }
}
