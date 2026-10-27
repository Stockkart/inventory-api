package com.inventory.hr.rest.dto.request;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /api/v1/hr/advances} (R7.1). {@code paidBy}: CASH, BANK or UPI. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordAdvanceRequest {
  private String employeeId;
  /** yyyy-MM-dd, not in the future. */
  private String date;
  private BigDecimal amount;
  /** Recovered per payroll month, 0 &lt; monthlyDeduction ≤ amount. */
  private BigDecimal monthlyDeduction;
  private String paidBy;
  private String note;
}
