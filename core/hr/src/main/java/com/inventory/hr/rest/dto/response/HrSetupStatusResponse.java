package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What the empty-state guide needs (R13.6): settings, then employees, then attendance. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HrSetupStatusResponse {
  private boolean settingsSaved;
  /** Active employees. */
  private long employeeCount;
  private boolean attendanceMarked;
}
