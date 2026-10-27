package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /api/v1/hr/payroll-runs}: draft for a month, or the run that already exists. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePayrollRunRequest {
  /** YYYY-MM, not after the current month. */
  private String period;
}
