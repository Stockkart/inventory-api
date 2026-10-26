package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One employee's pay in a run. Shown only to RUN_PAYROLL / RECORD_PAYMENTS, which both require
 * VIEW_SALARIES. {@code frozen} = fixed by finalise.
 */
@Data
@NoArgsConstructor
public class PayrollLineResponse {
  private String id;
  private String runId;
  private String employeeId;
  private String period;
  private PayrollLine.EmployeeSnapshot employeeSnapshot;
  private PayrollLine.DayCounts dayCounts;
  private Integer overtimeMinutes;
  private List<PayrollLine.EarningItem> earnings = new ArrayList<>();
  private List<PayrollLine.DeductionItem> deductions = new ArrayList<>();
  private List<PayrollLine.ManualItem> manualItems = new ArrayList<>();
  private BigDecimal advanceOverride;
  private List<PayrollLine.AdvanceRecovery> advanceRecoveries = new ArrayList<>();
  private BigDecimal advanceOutstandingAfter;
  private BigDecimal gross;
  private BigDecimal totalDeductions;
  private BigDecimal netBeforeRounding;
  private BigDecimal rounding;
  private BigDecimal net;
  private SalaryPaymentStatus paymentStatus;
  private Integer paymentSeq;
  private String paidAt;
  private HrPaymentMethod paymentMethod;
  private String paymentReference;
  private boolean frozen;
  private List<String> warnings = new ArrayList<>();
  private Instant updatedAt;
}
