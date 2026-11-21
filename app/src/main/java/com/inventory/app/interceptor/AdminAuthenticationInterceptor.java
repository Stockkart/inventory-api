package com.inventory.app.interceptor;

import com.inventory.app.observability.RequestMdc;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.user.api.AdminSessions;
import com.inventory.user.api.AdminSessions.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates admin sessions on {@code /api/v1/admin/**}. Shop-user tokens are not admin
 * sessions, so they get 401 here; {@link AuthenticationInterceptor} does not run on these paths.
 *
 * <p>Shop approval under {@code /api/v1/shops/admin/**} is not an admin-session path: it keeps the
 * normal sign-in and the SUPER_USER check ({@code ShopValidator.validateCanApproveShops}).
 */
@Component
public class AdminAuthenticationInterceptor implements HandlerInterceptor {

  /** Ant-style patterns for {@code InterceptorRegistry}. */
  public static final List<String> ADMIN_PATH_PATTERNS = List.of("/api/v1/admin/**");

  public static final String LOGIN_PATH = "/api/v1/admin/auth/login";
  /** Completes a password reset with the emailed one-time token; the caller has no session. */
  public static final String RESET_PASSWORD_PATH = "/api/v1/admin/auth/reset-password";
  /** Admin paths that need no admin session. */
  public static final List<String> PUBLIC_PATHS = List.of(LOGIN_PATH, RESET_PASSWORD_PATH);

  /** Reachable while the admin still has to replace a bootstrap or temporary password. */
  static final Set<String> PASSWORD_CHANGE_PATHS = Set.of(
      "/api/v1/admin/auth/me",
      "/api/v1/admin/auth/change-password",
      "/api/v1/admin/auth/logout");

  @Autowired
  private AdminSessions adminSessions;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Admin sign-in required");
    }
    AdminPrincipal auth = adminSessions.authenticate(authHeader.substring(7));

    String path = RequestPaths.lookupPath(request);
    if (RequestPaths.isSuspicious(request, path)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "Access denied");
    }
    if (auth.mustChangePassword() && !PASSWORD_CHANGE_PATHS.contains(path)) {
      throw new BaseException(ErrorCode.PASSWORD_CHANGE_REQUIRED, ErrorCode.PASSWORD_CHANGE_REQUIRED.getMessage());
    }

    request.setAttribute(AdminSessions.ADMIN_ID_ATTRIBUTE, auth.adminId());
    MDC.put(RequestMdc.USER_ID, "admin:" + auth.adminId());
    return true;
  }
}
