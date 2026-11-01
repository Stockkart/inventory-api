package com.inventory.plan.utils.constants;

import java.util.List;

public final class PlanPaymentConstants {

  private PlanPaymentConstants() {}

  public static final String PROVIDER_RAZORPAY = "razorpay";
  /** Orders the wallet pays in full; no gateway is involved. */
  public static final String PROVIDER_WALLET = "wallet";
  public static final String PAYMENT_METHOD_WALLET = "WALLET";

  /** Order written, reservations taken. */
  public static final String STATUS_CREATED = "CREATED";
  /** Provider order created; waiting for the customer to pay. */
  public static final String STATUS_PAYMENT_PENDING = "PAYMENT_PENDING";
  /** Payment confirmed. Durable before any side effect. */
  public static final String STATUS_PAID = "PAID";
  /** Claimed by one fulfilment attempt; see claimedAt. */
  public static final String STATUS_FULFILLING = "FULFILLING";
  public static final String STATUS_FULFILLED = "FULFILLED";
  /** Payment rejected or provider order failed; reservations released. */
  public static final String STATUS_PAYMENT_FAILED = "PAYMENT_FAILED";
  /** Not paid in time; reservations released. */
  public static final String STATUS_EXPIRED = "EXPIRED";
  /** Paid but not granted. Needs an operator; never cleared silently. */
  public static final String STATUS_FULFILMENT_FAILED = "FULFILMENT_FAILED";
  public static final String STATUS_CANCELLED = "CANCELLED";
  /** Paid, then refunded in full (gateway refund, lost dispute, or admin); grants reversed. */
  public static final String STATUS_REFUNDED = "REFUNDED";
  /** Pre-state-machine name for PAYMENT_FAILED; migrated on startup. */
  public static final String LEGACY_STATUS_FAILED = "FAILED";

  /** Awaiting payment. */
  public static final List<String> OPEN_STATUSES = List.of(STATUS_CREATED, STATUS_PAYMENT_PENDING);
  /** Ended without payment; a late confirmed payment is still accepted from these. */
  public static final List<String> UNPAID_ENDED_STATUSES =
      List.of(STATUS_PAYMENT_FAILED, STATUS_EXPIRED, STATUS_CANCELLED);
  /** Payment confirmed, whatever happened after. */
  public static final List<String> PAID_STATUSES =
      List.of(STATUS_PAID, STATUS_FULFILLING, STATUS_FULFILLED, STATUS_FULFILMENT_FAILED);
  /**
   * May move to REFUNDED. FULFILLING is left out: a running fulfilment could grant after the reversal,
   * so the refund waits for it (the gateway retries its webhook).
   */
  public static final List<String> REFUNDABLE_STATUSES =
      List.of(STATUS_PAID, STATUS_FULFILLED, STATUS_FULFILMENT_FAILED);

  /** Idempotency-Key header on checkout. */
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

  public static final String CURRENCY_INR = "INR";
  public static final int DEFAULT_CHECKOUT_DURATION_MONTHS = 12;
  /** Razorpay minimum order amount in paise (₹1). */
  public static final int MIN_AMOUNT_PAISE = 100;
}
