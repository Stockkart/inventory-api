package com.inventory.metrics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Increments a counter on each invocation. Use for request rate metrics.
 * Note: @Latency already provides count (request rate via Timer count).
 * Use this when you need a separate counter without timing.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RecordRequestRate {

  /**
   * Metric name. If empty, derived from class.method.
   */
  String value() default "";

  /**
   * Module name for dashboard filtering (e.g. "product", "user").
   */
  String module() default "";
}
