package com.inventory.plan.payment.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebhookHandleResult {

  public enum EventType {
    PAYMENT_CAPTURED,
    /** A refund the gateway has processed; may be partial. */
    REFUND_PROCESSED,
    /** A chargeback the merchant lost; treated as a full refund. */
    DISPUTE_LOST
  }

  private boolean processed;
  /** Null is read as PAYMENT_CAPTURED. */
  private EventType eventType;
  private String providerOrderId;
  private String providerPaymentId;
  private String paymentMethod;
  /** Refund or dispute id, for refund events. */
  private String providerRefundId;
  /** Refunded or disputed amount in rupees, for refund events. */
  private BigDecimal amount;

  public EventType effectiveEventType() {
    return eventType == null ? EventType.PAYMENT_CAPTURED : eventType;
  }
}
