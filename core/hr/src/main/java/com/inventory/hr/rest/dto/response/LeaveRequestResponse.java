package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.LeaveOrigin;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A leave request with the employee's code and name for lists. */
@Data
@NoArgsConstructor
public class LeaveRequestResponse {
  private String id;
  private String employeeId;
  private String employeeCode;
  private String employeeName;
  private String leaveTypeId;
  /** Display name of the leave type; null when the type no longer exists. */
  private String leaveTypeName;
  private String fromDate;
  private String toDate;
  private boolean halfDay;
  private BigDecimal days;
  private String reason;
  private String decisionNote;
  private LeaveRequestStatus status;
  private LeaveOrigin origin;
  private String requestedByUserId;
  private Instant decidedAt;
  private String decidedByUserId;
  private String leaveYear;
  private Instant createdAt;
}
