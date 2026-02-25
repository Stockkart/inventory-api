package com.inventory.app.interceptor;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.service.TokenValidationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class AuthenticationInterceptor implements HandlerInterceptor {

  // Public endpoints that don't require authentication
  private static final List<String> PUBLIC_ENDPOINTS = Arrays.asList(
      "/api/v1/auth/login",
      "/api/v1/auth/signup",
      "/api/v1/auth/change-password",
      "/api/v1/auth/accept-invite",
      "/api/product/get-plugin",
      "/api/product/",
      "/m/" // Mobile upload endpoints
  );
  @Autowired
  private TokenValidationService tokenValidationService;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    String requestPath = request.getRequestURI();
    String method = request.getMethod();

    // Allow OPTIONS requests (CORS preflight)
    if ("OPTIONS".equals(method)) {
      return true;
    }

    // Check if endpoint is public
    if (isPublicEndpoint(requestPath)) {
      log.debug("Public endpoint accessed: {}", requestPath);
      return true;
    }

    // Extract token from Authorization header
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.warn("Missing or invalid Authorization header for path: {}", requestPath);
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Authorization header with Bearer token is required");
    }

    String accessToken = authHeader.substring(7); // Remove "Bearer " prefix

    // Validate token and get user account
    try {
      UserAccount userAccount = tokenValidationService.validateToken(accessToken);

      // Store user information in request attributes for use in controllers
      request.setAttribute("userId", userAccount.getUserId());
      request.setAttribute("userRole", userAccount.getRole());
      request.setAttribute("shopId", userAccount.getShopId());
      request.setAttribute("userAccount", userAccount);
      request.setAttribute("accessToken", accessToken);

      log.debug("Authentication successful for user: {} on path: {}", userAccount.getUserId(), requestPath);
      return true;

    } catch (AuthenticationException e) {
      log.warn("Authentication failed for path {}: {}", requestPath, e.getMessage());
      throw e;
    }
  }

  private boolean isPublicEndpoint(String path) {
    return PUBLIC_ENDPOINTS.stream()
        .anyMatch(endpoint -> path.equals(endpoint) || path.startsWith(endpoint));
  }
}

