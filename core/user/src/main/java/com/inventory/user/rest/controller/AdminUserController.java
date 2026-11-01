package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.user.rest.dto.request.AdminCreateRequest;
import com.inventory.user.rest.dto.request.AdminUserActiveRequest;
import com.inventory.user.rest.dto.request.AdminUserReasonRequest;
import com.inventory.user.rest.dto.response.AdminPasswordIssuedResponse;
import com.inventory.user.rest.dto.response.AdminUserResponse;
import com.inventory.user.service.AdminAuthentication;
import com.inventory.user.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/admins")
@Latency(module = "user")
@RecordRequestRate(module = "user")
@RecordStatusCodes(module = "user")
public class AdminUserController {

  @Autowired
  private AdminUserService adminUserService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<AdminUserResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.success(adminUserService.list()));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AdminPasswordIssuedResponse>> create(
      @RequestBody AdminCreateRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminUserService.create(request, actor(httpRequest))));
  }

  @PatchMapping("/{id}/active")
  public ResponseEntity<ApiResponse<AdminUserResponse>> setActive(
      @PathVariable String id, @RequestBody AdminUserActiveRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        adminUserService.setActive(id, request, actor(httpRequest))));
  }

  @PostMapping("/{id}/reset-password")
  public ResponseEntity<ApiResponse<AdminPasswordIssuedResponse>> resetPassword(
      @PathVariable String id, @RequestBody(required = false) AdminUserReasonRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        adminUserService.resetPassword(id, request, actor(httpRequest))));
  }

  private static String actor(HttpServletRequest request) {
    return (String) request.getAttribute(AdminAuthentication.ADMIN_ID_ATTRIBUTE);
  }
}
