package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.rest.dto.request.UpdateHrSettingsRequest;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HrSettingsValidatorTest {

  private final HrSettingsValidator validator = new HrSettingsValidator();

  private static UpdateHrSettingsRequest withWeeklyOff(String from, List<DayOfWeek> days) {
    UpdateHrSettingsRequest r = new UpdateHrSettingsRequest();
    UpdateHrSettingsRequest.WeeklyOffChange c = new UpdateHrSettingsRequest.WeeklyOffChange();
    c.setEffectiveFrom(from);
    c.setDays(days);
    r.setWeeklyOff(c);
    return r;
  }

  @Test
  void anEmptyRequestIsValid() {
    assertDoesNotThrow(() -> validator.validate(new UpdateHrSettingsRequest()));
  }

  @Test
  void weeklyOffNeedsAMonthAndAtLeastOneWorkingDay() {
    assertThrows(ValidationException.class,
        () -> validator.validate(withWeeklyOff("2025-13", List.of(DayOfWeek.SUNDAY))));
    assertThrows(ValidationException.class,
        () -> validator.validate(withWeeklyOff("2025-04", Arrays.asList(DayOfWeek.values()))));
    assertThrows(ValidationException.class,
        () -> validator.validate(withWeeklyOff("2025-04",
            List.of(DayOfWeek.SUNDAY, DayOfWeek.SUNDAY))));
    assertThrows(ValidationException.class,
        () -> validator.validate(withWeeklyOff("2025-04", List.of())));
    assertDoesNotThrow(() -> validator.validate(withWeeklyOff("2025-04",
        List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))));
  }

  @Test
  void timesAndRangesAreChecked() {
    UpdateHrSettingsRequest r = new UpdateHrSettingsRequest();
    r.setWorkStart("24:00");
    assertThrows(ValidationException.class, () -> validator.validate(r));

    UpdateHrSettingsRequest grace = new UpdateHrSettingsRequest();
    grace.setGraceMinutes(-1);
    assertThrows(ValidationException.class, () -> validator.validate(grace));

    UpdateHrSettingsRequest half = new UpdateHrSettingsRequest();
    half.setHalfDayBelowHours(BigDecimal.ZERO);
    assertThrows(ValidationException.class, () -> validator.validate(half));

    UpdateHrSettingsRequest multiplier = new UpdateHrSettingsRequest();
    multiplier.setOvertimeMultiplier(new BigDecimal("0.9"));
    assertThrows(ValidationException.class, () -> validator.validate(multiplier));
  }

  @Test
  void workStartMustBeBeforeWorkEnd() {
    assertThrows(ValidationException.class, () -> validator.validateHours("21:00", "09:00"));
    assertThrows(ValidationException.class, () -> validator.validateHours("09:00", "09:00"));
    assertDoesNotThrow(() -> validator.validateHours("09:00", "21:00"));
  }

  @Test
  void theSalaryMonthStartsOnADayFrom1To28() {
    for (int day : new int[] {0, 29, 31, -1}) {
      UpdateHrSettingsRequest r = new UpdateHrSettingsRequest();
      r.setPayCycleStartDay(day);
      assertThrows(ValidationException.class, () -> validator.validate(r), "day " + day);
    }
    for (int day : new int[] {1, 26, 28}) {
      UpdateHrSettingsRequest r = new UpdateHrSettingsRequest();
      r.setPayCycleStartDay(day);
      assertDoesNotThrow(() -> validator.validate(r), "day " + day);
    }
  }
}
