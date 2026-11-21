package com.inventory.hr.rest.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /payroll-runs/{runId}/payslips/email} (R10.4): ≤ 300 lines of the run. */
@Data
@NoArgsConstructor
public class EmailPayslipsRequest {
  private List<String> lineIds = new ArrayList<>();
}
