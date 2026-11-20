package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.VoucherRedemptionStatus;
import com.inventory.plan.domain.model.VoucherTarget;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherRedemptionResponse {

  private String id;
  private String voucherCode;
  private String shopId;
  private String orderId;
  private VoucherTarget target;
  private String addOnCode;
  private String planCode;
  private BigDecimal discount;
  private VoucherRedemptionStatus status;
  private Instant reservedAt;
  private Instant redeemedAt;
  private Instant releasedAt;
}
