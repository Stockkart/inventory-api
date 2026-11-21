package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanTransactionResponse {

  private String id;
  private String shopId;
  private String planId;
  private String planName;
  private BigDecimal amount;
  private Integer durationMonths;
  private String paymentMethod;
  private String provider;
  private String providerPaymentId;
  /** Purchase date shown; paidAt (else createdAt) for order-only rows. Clients sort by {@code sortAt}. */
  private Instant createdAt;
  /** Null unless the payment was refunded. */
  private Instant refundedAt;
  private String planCode;
  /** PLAN, ADD_ON_ONLY or ASSIGNED (set by an admin or the assign endpoint; no verified charge). */
  private String kind;
  /** Order status; null for rows that have no order. */
  private String status;
  private List<PurchaseLineResponse> items;
  private BigDecimal subtotal;
  private BigDecimal discountTotal;
  private BigDecimal walletCredit;
  private Instant termStartsAt;
  private Instant termEndsAt;
  /** True when the term was derived from createdAt and the duration, not granted. */
  private boolean termEstimated;
  private String paymentOrderId;
  /** Money returned to the original payment method; null when nothing was. */
  private BigDecimal refundedToPaymentMethod;
  /** Wallet credit given back on a full refund; null otherwise. */
  private BigDecimal walletCreditReturned;
  /** Time the list is sorted by, newest first. */
  private Instant sortAt;
}
