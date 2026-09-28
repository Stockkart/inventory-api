package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.user.mapper.AdminUserMapper;
import com.inventory.user.rest.dto.request.AdminChangePasswordRequest;
import com.inventory.user.rest.dto.request.AdminLoginRequest;
import com.inventory.user.rest.dto.response.AdminLoginResponse;
import com.inventory.user.rest.dto.response.AdminUserResponse;
import com.inventory.user.service.AdminAuthService;
import com.inventory.user.service.AdminAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin sign-in. Everything except {@code /login} runs behind AdminAuthenticationInterceptor. */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Latency(module = "user")
@RecordRequestRate(module = "user")
@RecordStatusCodes(module = "user")
public class AdminAuthController {

  @Autowired
  private AdminAuthService adminAuthService;

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<AdminLoginResponse>> login(@RequestBody AdminLoginRequest request) {
    return ResponseEntity.ok(ApiResponse.success(adminAuthService.login(request)));
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
    adminAuthService.logout(auth(httpRequest));
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @GetMapping("/me")
  public ResponseEntity<ApiResponse<AdminUserResponse>> me(HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(AdminUserMapper.toResponse(auth(httpRequest).admin())));
  }

  @PostMapping("/change-password")
  public ResponseEntity<ApiResponse<AdminUserResponse>> changePassword(
      @RequestBody AdminChangePasswordRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        adminAuthService.changePassword(auth(httpRequest), request)));
  }

  private static AdminAuthentication auth(HttpServletRequest request) {
    return (AdminAuthentication) request.getAttribute(AdminAuthentication.REQUEST_ATTRIBUTE);
  }
}
