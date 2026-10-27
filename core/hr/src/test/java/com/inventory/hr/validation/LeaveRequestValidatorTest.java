package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LeaveRequestValidatorTest {

  private final LeaveRequestValidator validator = new LeaveRequestValidator();

  @Test
  void ninetyDaysIsTheLongestRequest() {
    LeaveRequestValidator.Range r =
        validator.validate("t1", "2025-01-01", "2025-03-31", null, null);
    assertEquals(LocalDate.of(2025, 3, 31), r.to());
    assertThrows(ValidationException.class,
        () -> validator.validate("t1", "2025-01-01", "2025-04-01", null, null));
  }

  @Test
  void halfDayOnlyForOneDate() {
    assertTrue(validator.validate("t1", "2025-04-08", "2025-04-08", true, null).halfDay());
    assertThrows(ValidationException.class,
        () -> validator.validate("t1", "2025-04-08", "2025-04-09", true, null));
  }

  @Test
  void endBeforeStartMissingTypeAndLongReasonAreRejected() {
    assertThrows(ValidationException.class,
        () -> validator.validate("t1", "2025-04-09", "2025-04-08", null, null));
    assertThrows(ValidationException.class,
        () -> validator.validate(" ", "2025-04-08", "2025-04-08", null, null));
    assertThrows(ValidationException.class,
        () -> validator.validate("t1", "2025-04-08", "2025-04-08", null, "x".repeat(501)));
  }
}
