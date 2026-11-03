package com.inventory.hr.rest.dto.response;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Employees with Unmarked working days in the run's month (R8.12): finalise is blocked until the
 * list is empty; the UI offers "mark them Present".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnmarkedDaysResponse {
  private String period;
  private List<Row> employees = new ArrayList<>();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Row {
    private String employeeId;
    private String code;
    private String name;
    /** yyyy-MM-dd, oldest first. */
    private List<String> dates = new ArrayList<>();
  }
}
