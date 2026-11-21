package com.inventory.app.interceptor;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.api.PlatformAdmins;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires the platform-admin role on {@code /api/v1/admin/**}. Runs after
 * {@link AuthenticationInterceptor}, which puts the {@code userId} request attribute in place.
 *
 * <p>Shop approval under {@code /api/v1/shops/admin/**} is not here: it stays on the normal
 * sign-in and the SUPER_USER check in {@code ShopValidator.validateCanApproveShops}.
 */
@Component
@Slf4j
public class PlatformRoleInterceptor implements HandlerInterceptor {

  /** Ant-style patterns for {@code InterceptorRegistry}; keep in sync with {@link #isAdminPath}. */
  public static final List<String> ADMIN_PATH_PATTERNS = List.of("/api/v1/admin/**");

  private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

  @Autowired
  private PlatformAdmins platformAdmins;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    String path = RequestPaths.lookupPath(request);
    if (RequestPaths.isSuspicious(request, path)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "Platform admin access required");
    }
    if (!isAdminPath(path)) {
      return true;
    }
    if (platformAdmins.isPlatformAdmin((String) request.getAttribute("userId"))) {
      return true;
    }
    log.warn("Platform admin required for {} (user {})", path, request.getAttribute("userId"));
    throw new BaseException(ErrorCode.ACCESS_DENIED, "Platform admin access required");
  }

  public static boolean isAdminPath(String path) {
    return path != null && path.startsWith(ADMIN_PATH_PREFIX);
  }
}
