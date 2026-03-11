package com.inventory.metrics.aspect;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.metrics.annotation.Latency;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LatencyAspect {

  private static final String METRIC_NAME = "inventory_api_latency";
  private static final double[] PERCENTILES = {0.5, 0.9, 0.95, 0.99};

  @Autowired
  private MetricsWrapper metrics;

  @Around("@annotation(latency) || @within(latency)")
  public Object recordLatency(ProceedingJoinPoint joinPoint, Latency latency) throws Throwable {
    String metricName = latency.value().isBlank()
        ? METRIC_NAME
        : latency.value();
    String endpoint = endpointName(joinPoint);
    String module = latency.module().isBlank() ? "default" : latency.module();

    Timer.Sample sample = Timer.start(metrics.getRegistry());
    try {
      return joinPoint.proceed();
    } finally {
      sample.stop(Timer.builder(metricName)
          .description("API latency")
          .tag("endpoint", endpoint)
          .tag("module", module)
          .publishPercentiles(PERCENTILES)
          .register(metrics.getRegistry()));
    }
  }

  private String endpointName(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    String className = joinPoint.getTarget().getClass().getSimpleName();
    String methodName = signature.getName();
    return className + "." + methodName;
  }
}
