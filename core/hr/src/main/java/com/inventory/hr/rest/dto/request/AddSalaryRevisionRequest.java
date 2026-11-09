package com.inventory.hr.rest.dto.request;

import com.inventory.hr.domain.model.enums.PayType;
import java.math.BigDecimal;
import lombok.Data;
import lombok.ToString;

/** New pay from a month on (R4.12); a revision for the same month replaces that one. */
@Data
public class AddSalaryRevisionRequest {
  /** YYYY-MM. */
  private String effectiveFrom;
  private PayType payType;
  @ToString.Exclude private BigDecimal amount;
}
