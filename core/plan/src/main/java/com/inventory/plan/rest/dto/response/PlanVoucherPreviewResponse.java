package com.inventory.plan.rest.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Live figures for a plan voucher before it is created. {@code message} is null when allowed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanVoucherPreviewResponse {

  private String planCode;
  private String planName;
  private BigDecimal price;
  private BigDecimal discount;
  private BigDecimal priceAfter;
  private BigDecimal basePrice;
  private BigDecimal floor;
  private BigDecimal maxFlat;
  private BigDecimal maxPercent;
  private boolean allowed;
  private String message;
}
