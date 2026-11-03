package com.inventory.hr.rest.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a leave request would cost before it is sent (R6.13): working days, and for a paid type
 * with a quota the balance left. {@code exceedsBalance} tells the UI to suggest an unpaid type.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeavePreviewResponse {
  private BigDecimal days;
  private boolean paid;
  /** null = no limit. */
  private BigDecimal available;
  private boolean exceedsBalance;
  private String leaveYear;
}
