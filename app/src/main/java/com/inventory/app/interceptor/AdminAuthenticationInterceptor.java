package com.inventory.app.interceptor;

import com.inventory.app.observability.RequestMdc;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.user.service.AdminAuthService;
import com.inventory.user.service.AdminAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates admin sessions on admin paths. Shop-user tokens are not admin sessions, so they get
 * 401 here; {@link AuthenticationInterceptor} does not run on these paths.
 */
@Component
public class AdminAuthenticationInterceptor implements HandlerInterceptor {

  /** Ant-style patterns for {@code InterceptorRegistry}. */
  public static final List<String> ADMIN_PATH_PATTERNS = List.of(
      "/api/v1/admin/**",
      "/api/v1/shops/admin/**");

  public static final String LOGIN_PATH = "/api/v1/admin/auth/login";

  /** Reachable while the admin still has to replace a bootstrap or temporary password. */
  static final Set<String> PASSWORD_CHANGE_PATHS = Set.of(
      "/api/v1/admin/auth/me",
      "/api/v1/admin/auth/change-password",
      "/api/v1/admin/auth/logout");

  @Autowired
  private AdminAuthService adminAuthService;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Admin sign-in required");
    }
    AdminAuthentication auth = adminAuthService.authenticate(authHeader.substring(7));

    if (auth.admin().isMustChangePassword() && !PASSWORD_CHANGE_PATHS.contains(request.getRequestURI())) {
      throw new BaseException(ErrorCode.PASSWORD_CHANGE_REQUIRED, ErrorCode.PASSWORD_CHANGE_REQUIRED.getMessage());
    }

    request.setAttribute(AdminAuthentication.REQUEST_ATTRIBUTE, auth);
    request.setAttribute(AdminAuthentication.ADMIN_ID_ATTRIBUTE, auth.adminId());
    MDC.put(RequestMdc.USER_ID, "admin:" + auth.adminId());
    return true;
  }
}
