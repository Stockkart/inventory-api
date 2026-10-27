package com.inventory.hr.rest.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code PUT /api/v1/hr/attendance/days}: up to 500 day marks in one call. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveAttendanceDaysRequest {

  private List<Row> rows = new ArrayList<>();

  /** One employee-day. {@code status} UNMARKED clears a stored mark. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Row {
    private String employeeId;
    /** yyyy-MM-dd, not in the future (Asia/Kolkata). */
    private String date;
    /** PRESENT, ABSENT, HALF_DAY, WEEK_OFF, HOLIDAY or UNMARKED. Leave goes through leave. */
    private String status;
    /** HH:mm; optional. */
    private String inTime;
    /** HH:mm; optional, after inTime. */
    private String outTime;
    private String note;
  }
}
