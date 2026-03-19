package com.inventory.metrics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records HTTP response status codes (200, 400, 500, etc.) for annotated controllers.
 * Place on controller class to record status for all its endpoints.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RecordStatusCodes {

  /**
   * Metric name. If empty, uses default inventory_api_status_total.
   */
  String value() default "";

  /**
   * Module name for dashboard filtering (e.g. "product", "user").
   */
  String module() default "";
}
