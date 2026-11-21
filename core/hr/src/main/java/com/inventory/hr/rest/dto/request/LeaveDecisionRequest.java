package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Approve, reject or cancel a leave request (R6.7); the note is optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveDecisionRequest {
  private String note;
}
