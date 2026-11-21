package com.inventory.hr.rest.dto.request;

import com.inventory.hr.domain.model.enums.ManualMarkReason;
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
  /**
   * Why the days are marked by hand (R7.2–R7.3); one reason for the whole call. Required only
   * when Biometric app check-in is on for the shop and at least one employee being marked has an
   * active paired phone; recorded on each of those employees' days.
   */
  private ManualMarkReason reason;
  /** Free text for {@link ManualMarkReason#OTHER}: 1–200 characters after trimming. */
  private String reasonText;

  /** A call without a reason (the existing shape). */
  public MarkUnmarkedPresentRequest(List<String> dates, List<String> employeeIds) {
    this(dates, employeeIds, null, null);
  }
}
