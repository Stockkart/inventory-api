package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.LeaveOrigin;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A leave request or recorded leave (R6). Approved leave overlays any stored day mark (D4). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_leave_requests")
public class LeaveRequest {

  @Id private String id;
  private String shopId;
  private String employeeId;
  private String leaveTypeId;
  /** yyyy-MM-dd. */
  private String fromDate;
  /** yyyy-MM-dd. */
  private String toDate;
  /** null = false; allowed only when fromDate = toDate. */
  private Boolean halfDay;
  /** Working days in the range (week-offs and holidays skipped), recomputed on approve. */
  private BigDecimal days;
  /** null = none. */
  private String reason;
  /** null = none. */
  private String decisionNote;
  private LeaveRequestStatus status;
  private String requestedByUserId;
  private LeaveOrigin origin;
  /** null = not decided. */
  private Instant decidedAt;
  /** null = not decided. */
  private String decidedByUserId;
  /** e.g. {@code 2025} or {@code 2025-26}, for balance grouping. */
  private String leaveYear;
  private Instant createdAt;
  private Instant updatedAt;
}
