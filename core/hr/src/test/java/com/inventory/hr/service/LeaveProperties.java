// Feature: hr-module, Property 3: Leave days count only working days; approve then cancel or reject leaves the balance unchanged
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Leave day counting and the derived balance (design Properties 6 and 7, task 6.4).
 *
 * <p><b>Validates: Requirements 6.4, 6.10, 6.11</b>
 */
class LeaveProperties {

  private static final LocalDate BASE = LocalDate.of(2025, 1, 1);

  @Property(tries = 100)
  void leaveDaysCountOnlyWorkingDays(
      @ForAll @IntRange(min = 0, max = 300) int start,
      @ForAll @IntRange(min = 0, max = 89) int length,
      @ForAll("weekOffs") Set<DayOfWeek> weekOffs,
      @ForAll("holidayOffsets") Set<Integer> holidayOffsets,
      @ForAll boolean halfDay) {
    LocalDate from = BASE.plusDays(start);
    LocalDate to = halfDay ? from : from.plusDays(length);
    Set<String> holidays = holidayOffsets.stream()
        .map(o -> HrDateUtils.format(BASE.plusDays(o))).collect(Collectors.toSet());
    HrSettings settings = HrSettingsService.withDefaults(null, SHOP);
    settings.setWeeklyOffSchedule(List.of(
        new HrSettings.WeeklyOffEntry("0000-01", weekOffs.stream().sorted().toList())));

    List<LocalDate> working = LeaveDayCounter.workingDates(from, to, holidays, settings);
    BigDecimal days = LeaveDayCounter.days(working, halfDay);

    long expected = 0;
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (!weekOffs.contains(d.getDayOfWeek()) && !holidays.contains(HrDateUtils.format(d))) {
        expected++;
      }
    }
    assertEquals(expected, working.size());
    for (LocalDate d : working) {
      assertTrue(!weekOffs.contains(d.getDayOfWeek()) && !holidays.contains(HrDateUtils.format(d)));
    }
    assertTrue(days.signum() >= 0);
    assertTrue(days.compareTo(BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1)) <= 0);
    if (halfDay) {
      assertEquals(expected == 0 ? BigDecimal.ZERO : new BigDecimal("0.5"), days);
    } else {
      assertEquals(BigDecimal.valueOf(expected), days);
    }
  }

  @Property(tries = 100)
  void firstYearQuotaIsAHalfDayMultipleAndNeverMoreThanTheQuota(
      @ForAll @IntRange(min = 0, max = 40) int quotaDays,
      @ForAll @IntRange(min = -400, max = 400) int joinOffset) {
    BigDecimal quota = BigDecimal.valueOf(quotaDays);
    LocalDate start = LocalDate.of(2025, 4, 1);
    LocalDate end = LocalDate.of(2026, 3, 31);
    BigDecimal q = LeaveBalanceService.proratedQuota(quota,
        HrDateUtils.format(start.plusDays(joinOffset)), start, end);

    assertTrue(q.signum() >= 0);
    assertTrue(q.compareTo(quota) <= 0);
    assertEquals(0, q.multiply(BigDecimal.valueOf(2)).remainder(BigDecimal.ONE).signum(),
        "multiple of 0.5: " + q);
    if (joinOffset <= 0) {
      assertEquals(0, q.compareTo(quota));
    }
  }

  @Property(tries = 100)
  void approveThenCancelOrRejectLeavesTheBalanceUnchanged(
      @ForAll("history") List<LeaveRequest> history,
      @ForAll @IntRange(min = 1, max = 10) int halfDays,
      @ForAll boolean cancelAfterApprove) {
    LeaveType type = new LeaveType();
    type.setId("casual");
    type.setPaid(true);
    type.setYearlyQuotaDays(new BigDecimal("12"));
    Employee employee = new Employee();
    employee.setJoinDate("2020-01-01");
    LocalDate start = LocalDate.of(2025, 1, 1);
    LocalDate end = LocalDate.of(2025, 12, 31);

    List<LeaveRequest> requests = new ArrayList<>(history);
    LeaveRequest request = request(LeaveRequestStatus.PENDING,
        BigDecimal.valueOf(halfDays).divide(BigDecimal.valueOf(2)));
    requests.add(request);
    BigDecimal before = available(type, employee, start, end, requests);

    request.setStatus(LeaveRequestStatus.APPROVED);
    BigDecimal approved = available(type, employee, start, end, requests);
    assertEquals(0, before.subtract(request.getDays()).compareTo(approved),
        "approving takes exactly the request's days");

    if (cancelAfterApprove) {
      request.setStatus(LeaveRequestStatus.CANCELLED);
    } else {
      // the other path: the same pending request rejected instead of approved
      request.setStatus(LeaveRequestStatus.REJECTED);
    }
    assertEquals(0, before.compareTo(available(type, employee, start, end, requests)));
  }

  private static BigDecimal available(LeaveType type, Employee employee, LocalDate start,
      LocalDate end, List<LeaveRequest> requests) {
    return LeaveBalanceService.balance(type, employee, start, end,
        LeaveBalanceService.usedDays(requests, type.getId())).available();
  }

  private static LeaveRequest request(LeaveRequestStatus status, BigDecimal days) {
    LeaveRequest r = new LeaveRequest();
    r.setLeaveTypeId("casual");
    r.setStatus(status);
    r.setDays(days);
    return r;
  }

  @Provide
  Arbitrary<Set<DayOfWeek>> weekOffs() {
    return Arbitraries.of(DayOfWeek.class).set().ofMaxSize(3);
  }

  @Provide
  Arbitrary<Set<Integer>> holidayOffsets() {
    return Arbitraries.integers().between(0, 400).set().ofMaxSize(20);
  }

  @Provide
  Arbitrary<List<LeaveRequest>> history() {
    return net.jqwik.api.Combinators.combine(
            Arbitraries.of(LeaveRequestStatus.class),
            Arbitraries.integers().between(1, 6),
            Arbitraries.of("casual", "sick"))
        .as((s, d, t) -> {
          LeaveRequest r = request(s, BigDecimal.valueOf(d));
          r.setLeaveTypeId(t);
          return r;
        })
        .list().ofMaxSize(8);
  }
}
