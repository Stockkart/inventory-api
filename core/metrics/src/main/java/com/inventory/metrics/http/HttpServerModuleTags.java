package com.inventory.metrics.http;

import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Low-cardinality module / URI helpers for Micrometer HTTP server meters. */
public final class HttpServerModuleTags {

  public static final String UNKNOWN = "UNKNOWN";
  public static final String OTHER = "other";

  private HttpServerModuleTags() {}

  public static String module(HttpServletRequest request) {
    Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
    if (handler instanceof HandlerMethod hm) {
      Class<?> type = hm.getBeanType();
      RecordRequestRate rate = type.getAnnotation(RecordRequestRate.class);
      if (rate != null && !rate.module().isBlank()) {
        return rate.module();
      }
      Latency latency = type.getAnnotation(Latency.class);
      if (latency != null && !latency.module().isBlank()) {
        return latency.module();
      }
    }
    return OTHER;
  }

  /** Spring path pattern only — never the raw URI (no ids). */
  public static String uri(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern instanceof String s && !s.isBlank()) {
      return s;
    }
    return UNKNOWN;
  }

  public static String method(HttpServletRequest request) {
    String method = request.getMethod();
    return method != null ? method : "UNKNOWN";
  }

  public static boolean skip(HttpServletRequest request) {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI() == null ? "" : request.getRequestURI();
    return path.startsWith("/actuator") || path.startsWith("/error");
  }
}
