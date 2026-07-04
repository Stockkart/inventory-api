package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Checkout order created before a plan payment is collected.
 * Fulfillment ({@link PlanService#assignPlan}) runs only after payment is verified.
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
  private String planName;
  private BigDecimal amount;
  private String currency;
  private Integer durationMonths;
  /** Payment gateway id, e.g. razorpay. */
  private String provider;
  private String providerOrderId;
  private String providerPaymentId;
  /** CREATED | PAID | FULFILLED | FAILED */
  private String status;
  private Instant createdAt;
  private Instant paidAt;
  private Instant fulfilledAt;
}
