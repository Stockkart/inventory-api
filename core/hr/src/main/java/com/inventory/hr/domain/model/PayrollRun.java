package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.PayrollRunStatus;
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

/** One payroll run per shop and period (R8.1); unique {@code (shopId, period)}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_payroll_runs")
public class PayrollRun {

  @Id private String id;
  private String shopId;
  /** YYYY-MM. */
  private String period;
  private PayrollRunStatus status;
  /** +1 on each reopen; null = 0. */
  private Integer revision;
  /** Claim time of FINALISING/REOPENING; null = no claim. Stale after 2 minutes. */
  private Instant claimedAt;
  /** null = not computed. */
  @ToString.Exclude private Totals totals;
  /** PAYROLL_RUN journal of the current revision; null = not posted or a zero run. */
  private String journalEntryId;
  private List<JournalHistoryEntry> journalHistory = new ArrayList<>();
  private List<ReopenHistoryEntry> reopenHistory = new ArrayList<>();
  private Instant calculatedAt;
  private Instant finalisedAt;
  private String finalisedByUserId;
  private Instant createdAt;
  private String createdByUserId;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Totals {
    private BigDecimal gross;
    private BigDecimal deductions;
    private BigDecimal advanceRecovered;
    private BigDecimal rounding;
    private BigDecimal net;
    private Integer employeeCount;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class JournalHistoryEntry {
    private Integer revision;
    private String journalEntryId;
    /** null = not reversed. */
    private String reversalEntryId;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ReopenHistoryEntry {
    private Instant at;
    private String byUserId;
    private String reason;
  }
}
