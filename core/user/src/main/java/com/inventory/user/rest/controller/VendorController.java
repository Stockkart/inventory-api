package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.vendor.CreateVendorRequest;
import com.inventory.user.rest.dto.vendor.CreateVendorResponse;
import com.inventory.user.rest.dto.vendor.SearchVendorRequest;
import com.inventory.user.rest.dto.vendor.VendorDto;
import com.inventory.user.service.VendorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
  public ResponseEntity<ApiResponse<VendorDto>> search(
      @RequestParam(required = false) String phone,
      @RequestParam(required = false) String email,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    // Create SearchVendorRequest from query parameters
    SearchVendorRequest request = new SearchVendorRequest();
    request.setPhone(phone);
    request.setEmail(email);

    VendorDto response = vendorService.searchVendor(request, shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

