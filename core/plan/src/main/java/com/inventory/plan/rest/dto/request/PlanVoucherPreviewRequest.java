package com.inventory.plan.rest.dto.request;

import com.inventory.plan.domain.model.VoucherType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What the admin has typed so far for a plan voucher; nothing is saved. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanVoucherPreviewRequest {

  private String planCode;
  private VoucherType type;
  private BigDecimal value;
}
