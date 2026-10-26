package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One of the caller's own payslips ({@code GET /me/payslips}): finalised months only. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyPayslipResponse {
  private String lineId;
  private String runId;
  /** YYYY-MM. */
  private String period;
  /** Own pay, so shown (R10.5). */
  private BigDecimal net;
  private SalaryPaymentStatus paymentStatus;
  /** yyyy-MM-dd; null = not paid yet. */
  private String paidAt;
  /** yyyy-MM-dd, first day of the salary month. */
  private String fromDate;
  /** yyyy-MM-dd, last day of the salary month. */
  private String toDate;
}
