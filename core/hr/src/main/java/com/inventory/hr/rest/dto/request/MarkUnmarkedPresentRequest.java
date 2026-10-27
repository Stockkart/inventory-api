package com.inventory.hr.rest.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/attendance/mark-unmarked-present}: the daily screen sends one date, the
 * payroll unmarked list every date of the month (R5.10, R8.12).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarkUnmarkedPresentRequest {

  /** 1 to 31 yyyy-MM-dd dates of one month, none in the future. */
  private List<String> dates = new ArrayList<>();
  /** null or empty = every employee employed on those dates. */
  private List<String> employeeIds;
}
