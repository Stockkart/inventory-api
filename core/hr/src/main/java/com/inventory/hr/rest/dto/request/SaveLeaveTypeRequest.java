package com.inventory.hr.rest.dto.request;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class SaveLeaveTypeRequest {
  private String name;
  private Boolean paid;
  /** Days per leave year; null = no limit. */
  private BigDecimal yearlyQuotaDays;
  /** null = active on create, unchanged on update. */
  private Boolean active;
}
