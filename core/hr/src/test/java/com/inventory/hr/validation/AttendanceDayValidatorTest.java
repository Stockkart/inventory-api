package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceDayValidatorTest {

  private static final LocalDate TODAY = LocalDate.of(2025, 4, 15);
  private final AttendanceDayValidator validator = new AttendanceDayValidator();

  private static SaveAttendanceDaysRequest save(SaveAttendanceDaysRequest.Row... rows) {
    return new SaveAttendanceDaysRequest(new ArrayList<>(List.of(rows)));
  }

  private static SaveAttendanceDaysRequest.Row row(String date, String status) {
    return new SaveAttendanceDaysRequest.Row("e1", date, status, null, null, null);
  }

  @Test
  void aNormalRowPasses() {
    SaveAttendanceDaysRequest.Row r = row("2025-04-15", "present");
    r.setInTime("09:00");
    r.setOutTime("18:00");
    assertDoesNotThrow(() -> validator.validateSave(save(r), TODAY));
  }

  @Test
  void futureDatesAreRejected() {
    assertThrows(ValidationException.class,
        () -> validator.validateSave(save(row("2025-04-16", "PRESENT")), TODAY));
  }

  @Test
  void moreThan500RowsAreRejected() {
    List<SaveAttendanceDaysRequest.Row> rows = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      rows.add(new SaveAttendanceDaysRequest.Row("e" + i, "2025-04-15", "PRESENT", null, null,
          null));
    }
    assertThrows(ValidationException.class,
        () -> validator.validateSave(new SaveAttendanceDaysRequest(rows), TODAY));
  }

  @Test
  void duplicatesBadTimesAndLeaveAreRejected() {
    assertThrows(ValidationException.class, () -> validator.validateSave(
        save(row("2025-04-15", "PRESENT"), row("2025-04-15", "ABSENT")), TODAY));
    SaveAttendanceDaysRequest.Row backwards = row("2025-04-15", "PRESENT");
    backwards.setInTime("18:00");
    backwards.setOutTime("09:00");
    assertThrows(ValidationException.class, () -> validator.validateSave(save(backwards), TODAY));
    SaveAttendanceDaysRequest.Row badTime = row("2025-04-15", "PRESENT");
    badTime.setInTime("24:00");
    assertThrows(ValidationException.class, () -> validator.validateSave(save(badTime), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validateSave(save(row("2025-04-15", "LEAVE")), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validateSave(save(row("2025-02-30", "PRESENT")), TODAY));
  }

  @Test
  void unmarkedMeansClear() {
    assertNull(validator.parseStatus("UNMARKED"));
    assertEquals(AttendanceStatus.HALF_DAY, validator.parseStatus("half_day"));
  }

  @Test
  void markUnmarkedTakesUpTo31Dates() {
    List<LocalDate> dates = validator.validateMarkUnmarked(new MarkUnmarkedPresentRequest(
        new ArrayList<>(List.of("2025-04-02", "2025-04-01", "2025-04-02")), null), TODAY);
    assertEquals(List.of(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 2)), dates);
    // a salary month (PayCycle) may span two calendar months, so that is allowed now
    assertEquals(2, validator.validateMarkUnmarked(new MarkUnmarkedPresentRequest(
        new ArrayList<>(List.of("2025-03-31", "2025-04-01")), null), TODAY).size());
    assertThrows(ValidationException.class, () -> validator.validateMarkUnmarked(
        new MarkUnmarkedPresentRequest(new ArrayList<>(List.of("2025-04-16")), null), TODAY));
    assertThrows(ValidationException.class, () -> validator.validateMarkUnmarked(
        new MarkUnmarkedPresentRequest(new ArrayList<>(), null), TODAY));
  }
}
