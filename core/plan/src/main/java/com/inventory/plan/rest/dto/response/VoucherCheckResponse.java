package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.model.VoucherType;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** "Is this code real" for the checkout form. The money comes from the quote, not from here. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherCheckResponse {

  private String code;
  private boolean valid;
  /** Set when {@code valid} is false. */
  private VoucherRejection reason;
  private String addOnCode;
  private VoucherType type;
  private BigDecimal value;
  private Integer quantity;
  private Instant validTo;
}
