package com.inventory.plan.rest.dto.request;

import lombok.Data;

@Data
public class VerifyPlanPaymentRequest {

  /** Internal plan payment order id. */
  private String orderId;
  private String razorpayPaymentId;
  private String razorpayOrderId;
  private String razorpaySignature;
}
