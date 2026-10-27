package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /api/v1/hr/leave-requests}: leave recorded for an employee, approved at once (R6.6). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordLeaveRequest {
  private String employeeId;
  private String leaveTypeId;
  /** yyyy-MM-dd. */
  private String fromDate;
  /** yyyy-MM-dd. */
  private String toDate;
  /** null = false; only when fromDate = toDate. */
  private Boolean halfDay;
  private String reason;
}
