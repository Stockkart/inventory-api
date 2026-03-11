package com.inventory.metrics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records latency (p50, p90, p95, p99) and request count for annotated methods.
 * Timer count can be used for request rate in Prometheus.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Latency {

  /**
   * Metric name. If empty, derived from class.method.
   */
  String value() default "";

  /**
   * Module name for dashboard filtering (e.g. "product", "user").
   */
  String module() default "";
}
