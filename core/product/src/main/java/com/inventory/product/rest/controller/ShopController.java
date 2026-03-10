package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.request.RegisterShopRequest;
import com.inventory.product.rest.dto.request.ShopApprovalRequest;
import com.inventory.product.rest.dto.response.ShopApprovalResponse;
import com.inventory.product.rest.dto.response.ShopRegistrationResponse;
import com.inventory.product.service.ShopService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class ShopController {

  @Autowired
  private ShopService shopService;

  @PostMapping("/api/v1/shops/register")
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
}

