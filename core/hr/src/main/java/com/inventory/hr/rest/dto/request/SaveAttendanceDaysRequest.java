package com.inventory.hr.rest.dto.request;

import com.inventory.hr.domain.model.enums.ManualMarkReason;
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
    /**
     * Why this day is marked by hand (R7.2–R7.3). Required only when Biometric app check-in is on
     * for the shop and the employee has an active paired phone at save time; ignored otherwise
     * unless given, in which case it is still recorded in HR history.
     */
    private ManualMarkReason reason;
    /** Free text for {@link ManualMarkReason#OTHER}: 1–200 characters after trimming. */
    private String reasonText;

    /** A row without a reason (the existing shape). */
    public Row(String employeeId, String date, String status, String inTime, String outTime,
        String note) {
      this(employeeId, date, status, inTime, outTime, note, null, null);
    }
  }
}
