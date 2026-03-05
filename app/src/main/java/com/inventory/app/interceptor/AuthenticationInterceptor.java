package com.inventory.app.interceptor;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.service.TokenValidationService;
import com.inventory.user.service.UserShopMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class AuthenticationInterceptor implements HandlerInterceptor {

  public static final String HEADER_X_SHOP_ID = "X-Shop-Id";

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

  @Autowired(required = false)
  private UserShopMembershipService membershipService;

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

      // Resolve effective shopId: X-Shop-Id header if valid, else user's active shop (backward compatible)
      String effectiveShopId = resolveEffectiveShopId(userAccount, request);

      request.setAttribute("userId", userAccount.getUserId());
      request.setAttribute("userRole", userAccount.getRole());
      request.setAttribute("shopId", effectiveShopId);
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
    if (PUBLIC_ENDPOINTS.stream().anyMatch(endpoint -> path.equals(endpoint) || path.startsWith(endpoint))) {
      return true;
    }
    // Plans: list and get-by-id public for pricing page before login
    if (path.equals("/api/v1/plans") || path.matches("/api/v1/plans/[a-fA-F0-9]{24}")) {
      return true;
    }
    return false;
  }

  /**
   * Resolve effective shopId for multi-shop. If X-Shop-Id header is present and user has access,
   * use it. Otherwise use userAccount.shopId (active shop).
   */
  private String resolveEffectiveShopId(UserAccount userAccount, HttpServletRequest request) {
    String headerShopId = request.getHeader(HEADER_X_SHOP_ID);
    if (StringUtils.hasText(headerShopId)) {
      headerShopId = headerShopId.trim();
      if (membershipService != null && membershipService.hasAccess(userAccount.getUserId(), headerShopId)) {
        return headerShopId;
      }
      log.debug("X-Shop-Id {} not accessible for user {}, using active shop", headerShopId, userAccount.getUserId());
    }
    return userAccount.getShopId();
  }
}

