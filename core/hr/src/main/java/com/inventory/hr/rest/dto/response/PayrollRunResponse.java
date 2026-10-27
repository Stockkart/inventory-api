package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A payroll run; {@code lines} only on the single-run read and actions, null in the list. */
@Data
@NoArgsConstructor
public class PayrollRunResponse {
  private String id;
  private String period;
  /** yyyy-MM-dd, first day of the salary month. */
  private String fromDate;
  /** yyyy-MM-dd, last day of the salary month. */
  private String toDate;
  private PayrollRunStatus status;
  private Integer revision;
  private PayrollRun.Totals totals;
  private String journalEntryId;
  private List<PayrollRun.JournalHistoryEntry> journalHistory = new ArrayList<>();
  private List<PayrollRun.ReopenHistoryEntry> reopenHistory = new ArrayList<>();
  private Instant calculatedAt;
  private Instant finalisedAt;
  private String finalisedByUserId;
  private Instant createdAt;
  private String createdByUserId;
  private List<PayrollLineResponse> lines;
}
