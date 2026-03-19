package com.inventory.metrics.interceptor;

import com.inventory.metrics.annotation.RecordStatusCodes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class StatusCodesMetricsInterceptor implements HandlerInterceptor {

  private static final String DEFAULT_METRIC_NAME = "inventory_api_status_total";

  @Autowired
  private MeterRegistry registry;

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception ex) {
    if (!(handler instanceof HandlerMethod)) {
      return;
    }
    HandlerMethod hm = (HandlerMethod) handler;
    Class<?> controllerClass = hm.getBeanType();

    RecordStatusCodes annotation = controllerClass.getAnnotation(RecordStatusCodes.class);
    if (annotation == null) {
      return;
    }

    String metricName = annotation.value().isBlank() ? DEFAULT_METRIC_NAME : annotation.value();
    String endpoint = controllerClass.getSimpleName() + "." + hm.getMethod().getName();
    String module = annotation.module().isBlank() ? "default" : annotation.module();
    int status = response.getStatus();
    String statusClass = statusClass(status);

    Counter.builder(metricName)
        .description("API responses by status code")
        .tag("endpoint", endpoint)
        .tag("module", module)
        .tag("status", String.valueOf(status))
        .tag("status_class", statusClass)
        .register(registry)
        .increment();
  }

  private String statusClass(int status) {
    if (status >= 100 && status < 200) return "1xx";
    if (status >= 200 && status < 300) return "2xx";
    if (status >= 300 && status < 400) return "3xx";
    if (status >= 400 && status < 500) return "4xx";
    if (status >= 500 && status < 600) return "5xx";
    return "other";
  }
}
