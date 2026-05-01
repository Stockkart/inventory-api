package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceDetailDto;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceListResponse;
import com.inventory.product.service.VendorPurchaseInvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vendor-purchase-invoices")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class VendorPurchaseInvoiceController {

  @Autowired
  private VendorPurchaseInvoiceService vendorPurchaseInvoiceService;

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<VendorPurchaseInvoiceListResponse>> list(
      HttpServletRequest httpRequest,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false, name = "q") String q) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
    return ResponseEntity.ok(
        ApiResponse.success(vendorPurchaseInvoiceService.list(shopId, page, size, q)));
  }

  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<VendorPurchaseInvoiceDetailDto>> getById(
      @PathVariable String id, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED, "User not authenticated or shop not found");
    }
    return ResponseEntity.ok(
        ApiResponse.success(vendorPurchaseInvoiceService.getById(id, shopId)));
  }
}
