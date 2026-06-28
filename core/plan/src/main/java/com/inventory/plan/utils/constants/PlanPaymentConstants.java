package com.inventory.plan.utils.constants;

public final class PlanPaymentConstants {

  private PlanPaymentConstants() {}

  public static final String PROVIDER_RAZORPAY = "razorpay";

  public static final String STATUS_CREATED = "CREATED";
  public static final String STATUS_PAID = "PAID";
  public static final String STATUS_FULFILLED = "FULFILLED";
  public static final String STATUS_FAILED = "FAILED";

  public static final String CURRENCY_INR = "INR";
  public static final int DEFAULT_CHECKOUT_DURATION_MONTHS = 12;
  /** Razorpay minimum order amount in paise (₹1). */
  public static final int MIN_AMOUNT_PAISE = 100;
}
