package com.inventory.hr.rest.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * {@code POST /payroll-runs/{runId}/payments} (R9.1): up to 50 lines by id, or {@code all: true}
 * for the next 50 unpaid lines; one journal for the whole call (decision 6).
 */
@Data
@NoArgsConstructor
public class RecordSalaryPaymentsRequest {
  private List<String> lineIds = new ArrayList<>();
  /** true = the next 50 PENDING lines of the run; {@code lineIds} must then be empty. */
  private Boolean all;
  /** yyyy-MM-dd, not in the future. */
  private String date;
  /** CASH, BANK or UPI. */
  private String method;
  /** Cheque, UTR or UPI reference; optional, ≤ 100 characters. */
  @ToString.Exclude private String reference;
}
