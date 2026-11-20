package com.inventory.plan.utils.constants;

import java.math.BigDecimal;
import java.time.Duration;

public final class PricingConstants {

  private PricingConstants() {}

  /** Bump whenever a pricing rule changes, so old orders can be reasoned about under their own rules. */
  public static final int PRICING_VERSION = 2;
  public static final Duration QUOTE_TTL = Duration.ofMinutes(15);
  /** listPrice = arcPrice + this, derived at read time (§7, §26.6). */
  public static final BigDecimal LIST_PRICE_MARKUP = new BigDecimal("3000");

  public static final String ITEM_TYPE_PLAN = "PLAN";
  public static final String ITEM_TYPE_ADDON = "ADDON";
  public static final String ITEM_TYPE_OCR_TOPUP = "OCR_TOPUP";
  public static final String ITEM_SOURCE_MANUAL = "MANUAL";
  public static final String ITEM_SOURCE_VOUCHER = "VOUCHER";

  /**
   * Plan key an add-on-only cart hashes under (§27.6). Lowercase, so it can never equal a plan code,
   * which are always uppercase.
   */
  public static final String ADD_ON_ONLY_CART_KEY = "addon-only";
  public static final String MSG_EMPTY_CART = "Choose a plan or at least one add-on";
  public static final String MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN =
      "Add-ons can be bought on their own only on an active paid plan";
  /** Legacy plans are grandfathered into every feature with no user or OCR limits. */
  public static final String MSG_LEGACY_PLAN_HAS_EVERYTHING =
      "Your plan already includes every feature with unlimited users and OCR, so add-ons add nothing";
  /** StockKart does not send SMS yet, so SMS packs are not sold in any cart. */
  public static final String MSG_SMS_COMING_SOON = "SMS packs are coming soon";
}
