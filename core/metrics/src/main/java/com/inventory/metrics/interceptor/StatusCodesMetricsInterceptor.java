package com.inventory.metrics.interceptor;

import com.inventory.metrics.annotation.RecordStatusCodes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeoutException;

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
    String method = request.getMethod() != null ? request.getMethod() : "unknown";
    int status = response.getStatus();
    String statusClass = statusClass(status);
    String errorType = resolveErrorType(status, ex);

    Counter.builder(metricName)
        .description("API responses by status code")
        .tag("endpoint", endpoint)
        .tag("module", module)
        .tag("method", method)
        .tag("status", String.valueOf(status))
        .tag("status_class", statusClass)
        .tag("error_type", errorType)
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

  private String resolveErrorType(int status, Exception ex) {
    if (status >= 200 && status < 400) {
      return "success";
    }
    if (ex != null) {
      Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
      if (cause instanceof TimeoutException
          || cause.getClass().getName().contains("Timeout")
          || (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("timeout"))) {
        return "timeout";
      }
      if (cause instanceof DataAccessException
          || cause.getClass().getName().contains("Mongo")
          || cause.getClass().getName().contains("Connection")) {
        return "dependency";
      }
      if (cause instanceof ConstraintViolationException
          || cause instanceof MethodArgumentNotValidException
          || cause instanceof HttpMessageNotReadableException
          || cause.getClass().getName().contains("Validation")) {
        return "validation";
      }
    }
    if (status == 400) {
      return "validation";
    }
    if (status >= 400 && status < 500) {
      return "client_error";
    }
    return "server_error";
  }
}
