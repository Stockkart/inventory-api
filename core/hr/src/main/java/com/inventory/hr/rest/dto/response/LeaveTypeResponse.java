package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.LeaveSystemDefault;
import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class LeaveTypeResponse {
  private String id;
  private String name;
  private Boolean paid;
  /** null = no limit. */
  private BigDecimal yearlyQuotaDays;
  private Boolean active;
  /** null = made by the shop. */
  private LeaveSystemDefault systemDefault;
}
