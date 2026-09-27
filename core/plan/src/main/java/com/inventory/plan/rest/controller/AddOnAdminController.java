package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.AddOnActiveRequest;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;
import com.inventory.plan.rest.dto.request.AddOnGrantRequest;
import com.inventory.plan.rest.dto.response.AdminAddOnResponse;
import com.inventory.plan.rest.dto.response.ShopAddOnResponse;
import com.inventory.plan.service.AddOnAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

/** Platform admins only: PlatformRoleInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/addons")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class AddOnAdminController {

  @Autowired
  private AddOnAdminService addOnAdminService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<AdminAddOnResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.success(addOnAdminService.list()));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AdminAddOnResponse>> create(
      @RequestBody AddOnAdminRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(addOnAdminService.create(request, actor(httpRequest))));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<AdminAddOnResponse>> update(
      @PathVariable String id, @RequestBody AddOnAdminRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(addOnAdminService.update(id, request, actor(httpRequest))));
  }

  @PatchMapping("/{id}/active")
  public ResponseEntity<ApiResponse<AdminAddOnResponse>> setActive(
      @PathVariable String id, @RequestBody AddOnActiveRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(addOnAdminService.setActive(id, request, actor(httpRequest))));
  }

  @PostMapping("/grants")
  public ResponseEntity<ApiResponse<ShopAddOnResponse>> grant(
      @RequestBody AddOnGrantRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(addOnAdminService.grant(request, actor(httpRequest))));
  }

  @GetMapping("/grants")
  public ResponseEntity<ApiResponse<List<ShopAddOnResponse>>> listForShop(@RequestParam String shopId) {
    return ResponseEntity.ok(ApiResponse.success(addOnAdminService.listForShop(shopId)));
  }

  private static String actor(HttpServletRequest request) {
    return (String) request.getAttribute("userId");
  }
}
