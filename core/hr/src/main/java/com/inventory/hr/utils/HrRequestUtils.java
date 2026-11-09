package com.inventory.hr.utils;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Caller and tenant of an HR request. Both come only from the attributes {@code
 * AuthenticationInterceptor} sets (L6), never from a header, path or body.
 */
public final class HrRequestUtils {

  private HrRequestUtils() {}

  public static String requireUserId(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return userId;
  }

  public static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
    return shopId;
  }
}
