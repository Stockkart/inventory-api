package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.request.ChangePasswordRequest;
import com.inventory.user.rest.dto.request.ForgotPasswordRequest;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.request.ResetPasswordRequest;
import com.inventory.user.rest.dto.request.SignupRequest;
import com.inventory.user.rest.dto.response.ChangePasswordResponse;
import com.inventory.user.rest.dto.response.ForgotPasswordResponse;
import com.inventory.user.rest.dto.response.LoginResponse;
import com.inventory.user.rest.dto.response.LogoutResponse;
import com.inventory.user.rest.dto.response.ResetPasswordResponse;
import com.inventory.user.rest.dto.response.SignupResponse;
import com.inventory.user.rest.dto.response.UserResponse;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.service.AuthService;
import com.inventory.user.service.UserShopMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  @Autowired
  private AuthService authService;

  @Autowired
  private UserMapper userMapper;

  @Autowired(required = false)
  private UserShopMembershipService membershipService;

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
  }

  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody SignupRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.signup(request)));
  }

  @PostMapping("/change-password")
  public ResponseEntity<ApiResponse<ChangePasswordResponse>> changePassword(
      @RequestBody ChangePasswordRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.changePassword(request)));
  }

  @PostMapping("/forgot-password")
  public ResponseEntity<ApiResponse<ForgotPasswordResponse>> forgotPassword(
      @RequestBody ForgotPasswordRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.forgotPassword(request)));
  }

  @PostMapping("/reset-password")
  public ResponseEntity<ApiResponse<ResetPasswordResponse>> resetPassword(
      @RequestBody ResetPasswordRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.resetPassword(request)));
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<LogoutResponse>> logout(HttpServletRequest request) {
    // Get userId and accessToken from request attributes (set by AuthenticationInterceptor)
    String userId = (String) request.getAttribute("userId");
    String accessToken = (String) request.getAttribute("accessToken");

    if (userId == null || accessToken == null) {
      throw new com.inventory.common.exception.AuthenticationException(
          com.inventory.common.constants.ErrorCode.UNAUTHORIZED,
          "User not authenticated");
    }

    return ResponseEntity.ok(ApiResponse.success(authService.logout(userId, accessToken)));
  }

  @GetMapping("/me")
  public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(HttpServletRequest request) {
    // User is already authenticated by interceptor, get from request attributes
    com.inventory.user.domain.model.UserAccount userAccount =
        (com.inventory.user.domain.model.UserAccount) request.getAttribute("userAccount");

    if (userAccount == null) {
      throw new com.inventory.common.exception.AuthenticationException(
          com.inventory.common.constants.ErrorCode.UNAUTHORIZED,
          "User not authenticated");
    }

    UserResponse response = userMapper.toUserResponse(userAccount);
    if (membershipService != null) {
      response.setShops(membershipService.getShopsForUser(userAccount.getUserId()).getData());
    }
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

