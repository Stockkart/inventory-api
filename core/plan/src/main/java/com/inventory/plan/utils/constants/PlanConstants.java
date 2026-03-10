package com.inventory.plan.utils.constants;

/**
 * Constants for plan and usage logic.
 */
public final class PlanConstants {

  private PlanConstants() {}

  /** Maximum number of usage months to retain per shop. Older months are purged. */
  public static final int MAX_USAGE_MONTHS_RETAINED = 12;

  /** Default duration in months when not specified. */
  public static final int DEFAULT_DURATION_MONTHS = 1;

  /** Default payment method when not specified. */
  public static final String DEFAULT_PAYMENT_METHOD = "CARD";

  /** Default duration in months for webhook. */
  public static final int WEBHOOK_DEFAULT_DURATION_MONTHS = 12;
}
