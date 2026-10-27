package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Self-service leave request (R6.5); the employee comes from the caller, never from the body. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestLeaveRequest {
  private String leaveTypeId;
  /** yyyy-MM-dd. */
  private String fromDate;
  /** yyyy-MM-dd. */
  private String toDate;
  /** null = false; only when fromDate = toDate. */
  private Boolean halfDay;
  private String reason;
}
