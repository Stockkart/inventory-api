package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.VoucherTarget;
import com.inventory.plan.domain.model.VoucherType;
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
public class AdminVoucherResponse {

  private String id;
  private String code;
  /** Never null: legacy vouchers without a target read as ADD_ON. */
  private VoucherTarget target;
  private String addOnCode;
  private String planCode;
  private String planName;
  private VoucherType type;
  private BigDecimal value;
  private int quantity;
  private Integer maxRedemptions;
  private int reservedCount;
  private int redemptionCount;
  private boolean singleUsePerShop;
  private String issuedToShopId;
  private Instant validFrom;
  private Instant validTo;
  private boolean active;
  private String note;
  private String batchId;
  private String createdByUserId;
  private Instant createdAt;
}
