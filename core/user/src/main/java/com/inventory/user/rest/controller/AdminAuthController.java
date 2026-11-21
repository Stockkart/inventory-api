package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.user.mapper.AdminUserMapper;
import com.inventory.user.rest.dto.request.AdminChangePasswordRequest;
import com.inventory.user.rest.dto.request.AdminLoginRequest;
import com.inventory.user.rest.dto.request.AdminResetPasswordRequest;
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

/**
 * Admin sign-in. Everything except {@code /login} and {@code /reset-password} runs behind
 * AdminAuthenticationInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Latency(module = "user")
@RecordRequestRate(module = "user")
@RecordStatusCodes(module = "user")
public class AdminAuthController {

  @Autowired
  private AdminAuthService adminAuthService;

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<AdminLoginResponse>> login(
      @RequestBody AdminLoginRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminAuthService.login(request, clientAddress(httpRequest))));
  }

  /** Public, like login: sets a new password with the one-time token from a reset email. */
  @PostMapping("/reset-password")
  public ResponseEntity<ApiResponse<AdminUserResponse>> resetPassword(
      @RequestBody AdminResetPasswordRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        adminAuthService.completePasswordReset(request, clientAddress(httpRequest))));
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

  /**
   * The client's address for sign-in limits: the last {@code X-Forwarded-For} entry (the one the
   * hosting proxy appends, which the client cannot choose), else the socket address.
   */
  private static String clientAddress(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String[] hops = forwarded.split(",");
      String last = hops[hops.length - 1].trim();
      if (!last.isEmpty()) {
        return last;
      }
    }
    return request.getRemoteAddr();
  }

  /** The interceptor has already checked the token; this loads the admin and session behind it. */
  private AdminAuthentication auth(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    return adminAuthService.authenticate(
        header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
  }
}
