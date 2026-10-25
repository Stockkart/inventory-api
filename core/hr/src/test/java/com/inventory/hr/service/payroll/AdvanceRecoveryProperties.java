// Feature: hr-module, Property 5: Recovery never exceeds outstanding or net pay; FIFO order; override respected
package com.inventory.hr.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DeductionKind;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Advance recovery bounds (design Property 10, task 8.3).
 *
 * <p><b>Validates: Requirements 7.2, 7.3, 7.4</b>
 */
class AdvanceRecoveryProperties {

  @Property(tries = 100)
  void recoveryStaysWithinOutstandingRoomAndFifoOrder(
      @ForAll("advances") List<PayrollInput.AdvanceBalance> advances,
      @ForAll("override") BigDecimal override,
      @ForAll("money") BigDecimal room) {
    PayrollCalculator.Allocation a = PayrollCalculator.allocate(advances, override, room);

    BigDecimal owed = advances.stream().map(PayrollInput.AdvanceBalance::outstanding)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    Map<String, PayrollInput.AdvanceBalance> byId = new HashMap<>();
    advances.forEach(x -> byId.put(x.advanceId(), x));
    Map<String, BigDecimal> taken = new HashMap<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (PayrollLine.AdvanceRecovery r : a.recoveries()) {
      PayrollInput.AdvanceBalance adv = byId.get(r.getAdvanceId());
      assertTrue(r.getAmount().signum() > 0);
      assertTrue(r.getAmount().compareTo(adv.outstanding()) <= 0, "never more than it owes");
      if (override == null) {
        assertTrue(r.getAmount().compareTo(adv.monthlyDeduction()) <= 0, "monthly amount");
      }
      taken.put(r.getAdvanceId(), r.getAmount());
      sum = sum.add(r.getAmount());
    }
    assertEquals(0, sum.compareTo(a.total()));
    assertTrue(a.total().compareTo(room) <= 0, "never more than the pay left");
    assertTrue(a.total().compareTo(owed) <= 0, "never more than outstanding");
    assertTrue(owed.subtract(a.total()).signum() >= 0);

    // FIFO: once an advance gets less than it could, every later one gets nothing
    List<PayrollInput.AdvanceBalance> fifo = new ArrayList<>(advances);
    fifo.sort(Comparator.comparing(PayrollInput.AdvanceBalance::date)
        .thenComparing(PayrollInput.AdvanceBalance::advanceId));
    boolean shortSeen = false;
    BigDecimal expectedAutomatic = BigDecimal.ZERO;
    for (PayrollInput.AdvanceBalance adv : fifo) {
      BigDecimal cap = override != null ? adv.outstanding()
          : adv.outstanding().min(adv.monthlyDeduction());
      expectedAutomatic = expectedAutomatic.add(adv.outstanding().min(adv.monthlyDeduction()));
      BigDecimal got = taken.getOrDefault(adv.advanceId(), BigDecimal.ZERO);
      if (shortSeen) {
        assertEquals(0, got.signum(), "a later advance before an earlier one is done");
      }
      if (got.compareTo(cap) < 0) {
        shortSeen = true;
      }
    }
    if (override != null) {
      // the override is the month's amount, bounded only by room and what is owed
      assertEquals(0, a.total().compareTo(override.min(room).min(owed)));
    } else {
      assertEquals(0, a.total().compareTo(expectedAutomatic.min(room)));
    }
  }

  @Property(tries = 100)
  void theLineNeverRecoversMoreThanPayLeftAfterOtherDeductions(
      @ForAll @IntRange(min = 0, max = 26) int presentDays,
      @ForAll("money") BigDecimal otherDeduction,
      @ForAll("advances") List<PayrollInput.AdvanceBalance> advances,
      @ForAll("override") BigDecimal override) {
    YearMonth month = YearMonth.of(2025, 4);
    Map<String, AttendanceDay> marks = new HashMap<>();
    int added = 0;
    for (int d = 1; d <= 30 && added < presentDays; d++) {
      String date = month.atDay(d).toString();
      if (month.atDay(d).getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
        marks.put(date, PayrollTestFixtures.mark("e", date, AttendanceStatus.PRESENT, null, null));
        added++;
      }
    }
    PayrollInput input = new PayrollInput(month,
        PayrollTestFixtures.settings(PerDayBasis.CALENDAR_DAYS, false), Set.of(),
        PayrollTestFixtures.leaveTypes(), List.of());
    PayrollInput.EmployeeInput in = new PayrollInput.EmployeeInput(
        PayrollTestFixtures.employee("e", PayType.DAILY, "700", "2024-01-01", null), marks,
        Map.of(), List.of(new PayrollLine.ManualItem(ManualItemKind.OTHER_DEDUCTION, null,
            otherDeduction)), override, advances);

    PayrollLine line = PayrollCalculator.calculate(input, in).line();

    BigDecimal advance = line.getDeductions().stream()
        .filter(x -> x.getKind() == DeductionKind.ADVANCE)
        .map(PayrollLine.DeductionItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal room = line.getGross().subtract(otherDeduction).max(BigDecimal.ZERO);
    assertTrue(advance.compareTo(room) <= 0);
    if (otherDeduction.compareTo(line.getGross()) <= 0) {
      assertTrue(line.getNetBeforeRounding().signum() >= 0, "net pay never below zero");
    }
    if (!advances.isEmpty()) {
      assertTrue(line.getAdvanceOutstandingAfter().signum() >= 0);
    }
  }

  @Provide
  Arbitrary<BigDecimal> money() {
    return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("50000")).ofScale(2);
  }

  @Provide
  Arbitrary<BigDecimal> override() {
    return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("20000")).ofScale(2)
        .injectNull(0.4);
  }

  @Provide
  Arbitrary<List<PayrollInput.AdvanceBalance>> advances() {
    Arbitrary<PayrollInput.AdvanceBalance> one = Combinators.combine(
        Arbitraries.strings().withCharRange('a', 'z').ofLength(6),
        Arbitraries.integers().between(1, 28),
        Arbitraries.bigDecimals().between(new BigDecimal("0.01"), new BigDecimal("5000"))
            .ofScale(2),
        Arbitraries.bigDecimals().between(new BigDecimal("0.01"), new BigDecimal("20000"))
            .ofScale(2))
        .as((id, day, monthly, outstanding) -> new PayrollInput.AdvanceBalance(id,
            String.format("2025-03-%02d", day), monthly, outstanding));
    return one.list().ofMaxSize(5).uniqueElements(PayrollInput.AdvanceBalance::advanceId);
  }
}
