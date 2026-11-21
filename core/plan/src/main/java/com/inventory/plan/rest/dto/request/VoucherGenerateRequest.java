package com.inventory.plan.rest.dto.request;

import com.inventory.plan.domain.model.VoucherType;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Creates one voucher with a chosen {@code code}, or {@code count} vouchers with generated codes
 * ({@code prefix}-XXXXXX) that share every other setting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherGenerateRequest {

  private String code;
  private Integer count;
  private String prefix;
  private String addOnCode;
  private VoucherType type;
  private BigDecimal value;
  private Integer quantity;
  private Integer maxRedemptions;
  private boolean singleUsePerShop;
  private String issuedToShopId;
  private Instant validFrom;
  private Instant validTo;
  private String note;
}
