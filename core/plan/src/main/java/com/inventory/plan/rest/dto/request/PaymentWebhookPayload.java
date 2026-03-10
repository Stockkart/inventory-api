package com.inventory.plan.rest.dto.request;

import lombok.Data;

/**
 * Payload from payment gateway webhook when payment succeeds.
 * Used to trigger plan assignment automatically from backend.
 */
@Data
public class PaymentWebhookPayload {

  private String shopId;
  private String planId;
  private Integer durationMonths;
  private String paymentMethod;
  /** Payment gateway transaction/reference ID for verification. */
  private String paymentId;
}
