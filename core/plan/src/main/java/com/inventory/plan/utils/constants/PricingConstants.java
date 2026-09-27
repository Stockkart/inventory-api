package com.inventory.plan.utils.constants;

import java.math.BigDecimal;
import java.time.Duration;

public final class PricingConstants {

  private PricingConstants() {}

  /** Bump whenever a pricing rule changes, so old orders can be reasoned about under their own rules. */
  public static final int PRICING_VERSION = 1;
  public static final Duration QUOTE_TTL = Duration.ofMinutes(15);
  /** listPrice = arcPrice + this, derived at read time (§7, §26.6). */
  public static final BigDecimal LIST_PRICE_MARKUP = new BigDecimal("3000");

  public static final String ITEM_TYPE_PLAN = "PLAN";
  public static final String ITEM_TYPE_ADDON = "ADDON";
  public static final String ITEM_TYPE_OCR_TOPUP = "OCR_TOPUP";
  public static final String ITEM_SOURCE_MANUAL = "MANUAL";
  public static final String ITEM_SOURCE_VOUCHER = "VOUCHER";
}
