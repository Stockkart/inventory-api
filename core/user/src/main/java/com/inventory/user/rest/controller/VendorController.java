package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.request.CreateVendorRequest;
import com.inventory.user.rest.dto.request.SearchVendorRequest;
import com.inventory.user.rest.dto.response.CreateVendorResponse;
import com.inventory.user.rest.dto.response.UserShopListResponse;
import com.inventory.user.rest.dto.response.VendorDto;
import com.inventory.user.mapper.VendorMapper;
import com.inventory.user.service.VendorService;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vendors")
@Slf4j
public class VendorController {

  @Autowired
  private VendorService vendorService;

  @Autowired
  private VendorMapper vendorMapper;

  @PostMapping
  public ResponseEntity<ApiResponse<CreateVendorResponse>> create(
      @RequestBody CreateVendorRequest request,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    CreateVendorResponse response = vendorService.createVendor(request, shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<List<VendorDto>>> search(
      @RequestParam("q") String query,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    SearchVendorRequest request = vendorMapper.toSearchVendorRequest(query);
    List<VendorDto> response = vendorService.searchVendor(request, shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Get shops for a vendor when the vendor is a StockKart user.
   * Must be declared before /{vendorId} to avoid path matching conflict.
   */
  @GetMapping("/{vendorId}/shops")
  public ResponseEntity<ApiResponse<UserShopListResponse>> getVendorShops(
      @PathVariable String vendorId,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!org.springframework.util.StringUtils.hasText(shopId)) {
      throw new com.inventory.common.exception.AuthenticationException(
          com.inventory.common.constants.ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    var response = vendorService.getShopsForVendor(vendorId, shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/{vendorId}")
  public ResponseEntity<ApiResponse<VendorDto>> getVendorById(
      @PathVariable String vendorId,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    VendorDto response = vendorService.getVendorById(vendorId, shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

