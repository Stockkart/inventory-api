package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.DeductionKind;
import com.inventory.hr.domain.model.enums.EarningKind;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One employee's pay in one run; unique {@code (runId, employeeId)}. Amounts are left out of
 * {@code toString} so they never reach a log line.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_payroll_lines")
public class PayrollLine {

  @Id private String id;
  private String shopId;
  private String runId;
  private String employeeId;
  /** YYYY-MM of the run. */
  private String period;
  /** yyyy-MM-dd range of the salary month this line was calculated for; null = calendar month. */
  private String fromDate;
  private String toDate;
  /** Frozen at finalise. */
  @ToString.Exclude private EmployeeSnapshot employeeSnapshot;
  private DayCounts dayCounts;
  /** null = 0. */
  private Integer overtimeMinutes;
  @ToString.Exclude private List<EarningItem> earnings = new ArrayList<>();
  @ToString.Exclude private List<DeductionItem> deductions = new ArrayList<>();
  /** Kept across recalculation; null = none. */
  @ToString.Exclude private List<ManualItem> manualItems = new ArrayList<>();
  /** R7.4; null = automatic FIFO amount. */
  @ToString.Exclude private BigDecimal advanceOverride;
  /** FIFO by advance date; null = none. */
  @ToString.Exclude private List<AdvanceRecovery> advanceRecoveries = new ArrayList<>();
  /** Printed on the payslip, frozen at finalise; null = no advances. */
  @ToString.Exclude private BigDecimal advanceOutstandingAfter;
  @ToString.Exclude private BigDecimal gross;
  @ToString.Exclude private BigDecimal totalDeductions;
  @ToString.Exclude private BigDecimal netBeforeRounding;
  @ToString.Exclude private BigDecimal rounding;
  @ToString.Exclude private BigDecimal net;
  /** null = PENDING. */
  private SalaryPaymentStatus paymentStatus;
  /** +1 per pay call; null = 0. */
  private Integer paymentSeq;
  /** ObjectId string, one per pay call; the batch journal's sourceId. null = not paid. */
  private String paymentBatchId;
  /** yyyy-MM-dd; null = not paid. */
  private String paidAt;
  private HrPaymentMethod paymentMethod;
  @ToString.Exclude private String paymentReference;
  private String paymentJournalEntryId;
  private String paidByUserId;
  /**
   * When the current PAYING or UNDOING claim was taken; null = no claim. A claim older than two
   * minutes belongs to a call that died and is finished by the next pay or undo call (task 9.1).
   */
  private Instant paymentClaimedAt;
  /**
   * Set by finalise to the run's claim time; null = draft line. Recalculation and adjustments only
   * write lines whose {@code frozenAt} is null, so a slow draft write can never overwrite the
   * snapshot a finalise has frozen. Cleared again by reopen.
   */
  private Instant frozenAt;
  /**
   * Things the reviewer should look at, e.g. {@code NO_SALARY} (no salary revision applies in the
   * period, so pay is zero) or {@code DEDUCTIONS_EXCEED_PAY}; null or empty = none.
   */
  private List<String> warnings = new ArrayList<>();
  private Instant updatedAt;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class EmployeeSnapshot {
    private String name;
    private String code;
    private String designation;
    private String joinDate;
    private String exitDate;
    private PayType payType;
    private BigDecimal rateAmount;
    /** Minutes in the employee's working day. */
    private Integer workingMinutes;
  }

  /** BigDecimal where halves occur. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DayCounts {
    private Integer employedDays;
    private BigDecimal present;
    private Integer halfDays;
    private BigDecimal paidLeave;
    private BigDecimal unpaidLeave;
    private BigDecimal absent;
    private BigDecimal weekOff;
    private BigDecimal holiday;
    /** PRESENT (1) and HALF_DAY (0.5) marks on a derived week-off or holiday; the off day itself
     *  is still counted in weekOff/holiday. Paid on top for daily wage always, for monthly only
     *  with overtime on. Stored as a number (was an integer before half days counted). */
    private BigDecimal offDayWorked;
    /** Days (or the other half of a half-day leave) with no status yet; finalise needs 0. */
    private BigDecimal unmarked;
    private BigDecimal paidDays;
    private Integer basisDays;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class EarningItem {
    private EarningKind kind;
    private String label;
    private BigDecimal amount;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DeductionItem {
    private DeductionKind kind;
    private String label;
    private BigDecimal amount;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ManualItem {
    private ManualItemKind kind;
    private String note;
    private BigDecimal amount;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AdvanceRecovery {
    private String advanceId;
    private BigDecimal amount;
  }
}
