package com.inventory.app.interceptor;

import com.inventory.common.entitlement.RequiresEntitlement;
import com.inventory.plan.service.EntitlementGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link RequiresEntitlement} on controllers and handler methods to the current shop.
 */
@Component
public class EntitlementInterceptor implements HandlerInterceptor {

  @Autowired
  private EntitlementGuard entitlementGuard;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod()) || !(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    RequiresEntitlement required = requiredEntitlement(handlerMethod);
    if (required == null) {
      return true;
    }
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      return true;
    }
    entitlementGuard.requireFeature(shopId, required.value());
    return true;
  }

  static RequiresEntitlement requiredEntitlement(HandlerMethod handlerMethod) {
    RequiresEntitlement onMethod =
        AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequiresEntitlement.class);
    if (onMethod != null) {
      return onMethod;
    }
    return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiresEntitlement.class);
  }
}
