package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Checkout order created before a plan payment is collected. Moves through the states in
 * {@link com.inventory.plan.utils.constants.PlanPaymentConstants}; every move is a conditional
 * update on the current status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plan_payment_orders")
public class PlanPaymentOrder {

  @Id
  private String id;
  private String shopId;
  private String planId;
  /** Null for legacy plans without a catalogue code. */
  private String planCode;
  private String planName;
  /** What the customer is charged: the priced cart's grand total. */
  private BigDecimal amount;
  private String currency;
  private Integer durationMonths;
  private List<OrderLine> items;
  private BigDecimal subtotal;
  private BigDecimal discountTotal;
  private BigDecimal walletCredit;
  private Integer pricingVersion;
  /** Client-chosen key; a retry with the same key and cart returns this order. */
  private String idempotencyKey;
  /** Hash of the canonical cart, to reject a reused key with a different cart. */
  private String requestHash;
  /** Payment gateway id, e.g. razorpay. */
  private String provider;
  private String providerOrderId;
  private String providerPaymentId;
  private String paymentMethod;
  /** See PlanPaymentConstants.STATUS_*. */
  private String status;
  /** Unpaid by then: EXPIRED, reservations released. */
  private Instant expiresAt;
  /** Paid after it had failed, expired or been cancelled; reservations must be taken again. */
  private boolean latePayment;
  /** When the current fulfilment attempt claimed the order; stale claims may be re-taken. */
  private Instant claimedAt;
  private int fulfilmentAttempts;
  private String failureReason;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant paidAt;
  private Instant fulfilledAt;
}
