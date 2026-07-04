package com.inventory.plan.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CreateCheckoutCommand {
  private String internalOrderId;
  private String shopId;
  private String planId;
  private String planName;
  private BigDecimal amount;
  private String currency;
  private Integer durationMonths;
  private String customerEmail;
  private String customerPhone;
}
