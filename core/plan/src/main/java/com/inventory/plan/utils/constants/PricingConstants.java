package com.inventory.plan.utils.constants;

import java.time.Duration;

public final class PricingConstants {

  private PricingConstants() {}

  /** Bump whenever a pricing rule changes, so old orders can be reasoned about under their own rules. */
  public static final int PRICING_VERSION = 1;
  public static final Duration QUOTE_TTL = Duration.ofMinutes(15);

  public static final String ITEM_TYPE_PLAN = "PLAN";
  public static final String ITEM_SOURCE_MANUAL = "MANUAL";
}
