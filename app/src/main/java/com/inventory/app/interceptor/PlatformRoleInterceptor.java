package com.inventory.app.interceptor;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.domain.model.PlatformRole;
import com.inventory.user.domain.model.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires {@link PlatformRole#PLATFORM_ADMIN} on platform-admin paths. Runs after
 * {@link AuthenticationInterceptor}, which puts the {@code userAccount} request attribute in place.
 */
@Component
@Slf4j
public class PlatformRoleInterceptor implements HandlerInterceptor {

  /** Ant-style patterns for {@code InterceptorRegistry}; keep in sync with {@link #isAdminPath}. */
  public static final List<String> ADMIN_PATH_PATTERNS = List.of(
      "/api/v1/admin/**",
      "/api/v1/shops/admin/**");

  private static final List<String> ADMIN_PATH_PREFIXES = List.of(
      "/api/v1/admin/",
      "/api/v1/shops/admin/");

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    if (!isAdminPath(path)) {
      return true;
    }
    Object account = request.getAttribute("userAccount");
    if (account instanceof UserAccount user && user.hasPlatformRole(PlatformRole.PLATFORM_ADMIN)) {
      return true;
    }
    log.warn("Platform admin required for {} (user {})", path, request.getAttribute("userId"));
    throw new BaseException(ErrorCode.ACCESS_DENIED, "Platform admin access required");
  }

  public static boolean isAdminPath(String path) {
    return path != null && ADMIN_PATH_PREFIXES.stream().anyMatch(path::startsWith);
  }
}
