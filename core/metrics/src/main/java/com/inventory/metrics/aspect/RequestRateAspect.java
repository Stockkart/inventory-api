package com.inventory.metrics.aspect;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.metrics.annotation.RecordRequestRate;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class RequestRateAspect {

  private static final String METRIC_NAME = "inventory_api_requests_total";

  @Autowired
  private MetricsWrapper metrics;

  @Around("@annotation(recordRequestRate) || @within(recordRequestRate)")
  public Object recordRequest(ProceedingJoinPoint joinPoint, RecordRequestRate recordRequestRate) throws Throwable {
    String metricName = recordRequestRate.value().isBlank()
        ? METRIC_NAME
        : recordRequestRate.value();
    String endpoint = endpointName(joinPoint);
    String module = recordRequestRate.module().isBlank() ? "default" : recordRequestRate.module();
    String method = resolveHttpMethod();

    Counter.builder(metricName)
        .description("API request count")
        .tag("endpoint", endpoint)
        .tag("module", module)
        .tag("method", method)
        .register(metrics.getRegistry())
        .increment();

    return joinPoint.proceed();
  }

  private String endpointName(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    String className = joinPoint.getTarget().getClass().getSimpleName();
    String methodName = signature.getName();
    return className + "." + methodName;
  }

  private String resolveHttpMethod() {
    try {
      var attrs = RequestContextHolder.getRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        HttpServletRequest request = sra.getRequest();
        if (request != null && request.getMethod() != null) {
          return request.getMethod();
        }
      }
    } catch (Exception ignored) {
      // fallback
    }
    return "unknown";
  }
}
