package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.auth.AcceptInviteRequest;
import com.inventory.user.rest.dto.auth.AcceptInviteResponse;
import com.inventory.user.rest.dto.auth.LoginRequest;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.dto.auth.LogoutResponse;
import com.inventory.user.rest.dto.auth.SignupRequest;
import com.inventory.user.rest.dto.auth.SignupResponse;
import com.inventory.user.rest.dto.auth.UserResponse;
import com.inventory.user.rest.mapper.UserMapper;
import com.inventory.user.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  @Autowired
  private AuthService authService;

  @Autowired
  private UserMapper userMapper;

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
  }

  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody SignupRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.signup(request)));
  }

  @PostMapping("/accept-invite")
  public ResponseEntity<ApiResponse<AcceptInviteResponse>> acceptInvite(@RequestBody AcceptInviteRequest request) {
    return ResponseEntity.ok(ApiResponse.success(authService.acceptAdminInvite(request)));
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
    
    // Map to response using mapper
    return ResponseEntity.ok(ApiResponse.success(userMapper.toUserResponse(userAccount)));
  }
}

