package com.inventory.app.interceptor;

import com.inventory.plan.api.PlanStatus;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Blocks authenticated shop-scoped API calls when the shop plan or trial has expired.
 * Plan renewal endpoints remain available so users can pay and reactivate.
 */
@Component
@Slf4j
public class PlanExpiryInterceptor implements HandlerInterceptor {

  private static final List<String> ALLOWED_PREFIXES = List.of(
      "/api/v1/plans",
      "/api/v1/auth/",
      "/api/v1/users/me/shops",
      "/api/v1/users/me/active-shop",
      "/api/v1/shops/active-shop",
      "/api/v1/shops/me/capabilities"
  );

  private static final List<Pattern> ALLOWED_PATTERNS = List.of(
      Pattern.compile("/api/v1/plans/[a-fA-F0-9]{24}"),
      Pattern.compile("/api/v1/plans/shop/[a-fA-F0-9]{24}/assign"),
      // HR decision 5: staff can still download their own payslips after the plan expired. A
      // pattern, not a prefix, so "/api/v1/hr/me/payslipsX" is not allowed by accident
      Pattern.compile("/api/v1/hr/me/payslips(/.*)?")
  );

  @Autowired
  private PlanStatus planStatus;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }

    String requestPath = request.getRequestURI();
    // allow-list matched on the decoded, semicolon-free path Spring routes on (see RequestPaths);
    // dot-segment paths never count as allowed
    String lookupPath = RequestPaths.lookupPath(request);
    if (!RequestPaths.isSuspicious(request, lookupPath) && isAllowedWhenExpired(lookupPath)) {
      return true;
    }

    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId) || planStatus == null) {
      return true;
    }

    if (!planStatus.isPlanExpired(shopId)) {
      return true;
    }

    log.info("Blocking expired plan access for shop {} on path {}", shopId, requestPath);
    throw new BaseException(
        ErrorCode.PLAN_EXPIRED,
        "Your plan or trial has expired. Please renew to continue.");
  }

  private boolean isAllowedWhenExpired(String path) {
    if (ALLOWED_PREFIXES.stream().anyMatch(path::startsWith)) {
      return true;
    }
    return ALLOWED_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(path).matches());
  }
}
