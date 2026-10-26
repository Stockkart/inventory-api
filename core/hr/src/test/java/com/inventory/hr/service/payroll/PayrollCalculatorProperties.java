// Feature: hr-module, Property 4: Gross − deductions = net before rounding; |rounding| ≤ 0.50; the finalise journal balances incl. 5500
package com.inventory.hr.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.EarningKind;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import com.inventory.hr.service.HrAccountingPoster;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Payroll money adds up exactly (design Properties 8 and 9, task 8.2).
 *
 * <p><b>Validates: Requirements 8.3, 8.4, 8.5, 8.15, 11.1, 14.2</b>
 */
class PayrollCalculatorProperties {

  /** One generated employee: pay, a status code per day of the month, manual items, advance. */
  record Spec(boolean daily, BigDecimal rate, List<Integer> days, BigDecimal bonus,
      BigDecimal deduction, BigDecimal advanceOutstanding, int joinDay) {}

  @Property(tries = 100)
  void moneyAddsUpAndTheRunJournalBalances(
      @ForAll("months") YearMonth month,
      @ForAll("bases") PerDayBasis basis,
      @ForAll boolean overtime,
      @ForAll("specs") List<Spec> specs) {
    PayrollInput input = new PayrollInput(month, PayrollTestFixtures.settings(basis, overtime),
        Set.of(month.atDay(10).toString()), PayrollTestFixtures.leaveTypes(), List.of());
    List<PayrollLine> finalisable = new ArrayList<>();
    int n = 0;
    for (Spec spec : specs) {
      PayrollLine line = PayrollCalculator.calculate(input, employeeInput("e" + n++, month,
          spec)).line();
      assertLineAddsUp(line, spec, basis, month);
      if (line.getNetBeforeRounding().signum() >= 0) {
        finalisable.add(line);
      }
    }
    assertJournalBalances(finalisable, month);
  }

  private static void assertLineAddsUp(PayrollLine line, Spec spec, PerDayBasis basis,
      YearMonth month) {
    BigDecimal earnings = line.getEarnings().stream().map(PayrollLine.EarningItem::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal deductions = line.getDeductions().stream()
        .map(PayrollLine.DeductionItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, earnings.compareTo(line.getGross()), "gross = Σ earnings");
    assertEquals(0, deductions.compareTo(line.getTotalDeductions()));
    assertEquals(0, line.getGross().subtract(line.getTotalDeductions())
        .compareTo(line.getNetBeforeRounding()), "gross − deductions = net before rounding");
    assertEquals(0, line.getNetBeforeRounding().add(line.getRounding()).compareTo(line.getNet()));
    assertTrue(line.getNet().stripTrailingZeros().scale() <= 0, "net is whole rupees");
    assertTrue(line.getRounding().abs().compareTo(new BigDecimal("0.50")) <= 0);
    for (PayrollLine.EarningItem e : line.getEarnings()) {
      assertTrue(e.getAmount().stripTrailingZeros().scale() <= 2, "paise");
    }
    PayrollLine.DayCounts c = line.getDayCounts();
    assertTrue(c.getPaidDays().signum() >= 0);
    if (!spec.daily() && basis != PerDayBasis.CALENDAR_DAYS) {
      assertTrue(c.getPaidDays().compareTo(BigDecimal.valueOf(c.getBasisDays())) <= 0,
          "paid days ≤ basis");
    }
    BigDecimal base = line.getEarnings().stream().filter(e -> e.getKind() == EarningKind.BASE)
        .findFirst().orElseThrow().getAmount();
    BigDecimal expected = spec.daily()
        ? spec.rate().multiply(c.getPaidDays()).setScale(2, RoundingMode.HALF_UP)
        : spec.rate().multiply(c.getPaidDays())
            .divide(BigDecimal.valueOf(PayrollCalculator.basisDays(basis, month)), 2,
                RoundingMode.HALF_UP);
    assertEquals(0, expected.compareTo(base), "base pay formula at paise");
  }

  /** The poster's PAYROLL_RUN entry: Σ debit = Σ credit, no negative amount, 5500 as needed. */
  private static void assertJournalBalances(List<PayrollLine> lines, YearMonth month) {
    AccountingFacade facade = mock(AccountingFacade.class);
    AtomicReference<PostJournalRequest> posted = new AtomicReference<>();
    when(facade.post(anyString(), anyString(), any(PostJournalRequest.class))).thenAnswer(i -> {
      posted.set(i.getArgument(2));
      return new JournalEntry();
    });
    HrAccountingPoster poster = new HrAccountingPoster();
    ReflectionTestUtils.setField(poster, "accountingFacade", facade);
    PayrollRun run = new PayrollRun();
    run.setId("run");
    run.setPeriod(month.toString());
    run.setRevision(0);

    JournalEntry entry = poster.postPayrollRun("shop", "user", run, lines);

    HrAccountingPoster.RunTotals t = HrAccountingPoster.runTotals(lines);
    if (entry == null) {
      assertEquals(0, t.expense().signum());
      return;
    }
    assertNotNull(posted.get());
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    BigDecimal roundOff = BigDecimal.ZERO;
    for (PostJournalLine l : posted.get().getLines()) {
      BigDecimal d = l.getDebit() != null ? l.getDebit() : BigDecimal.ZERO;
      BigDecimal c = l.getCredit() != null ? l.getCredit() : BigDecimal.ZERO;
      assertTrue(d.signum() >= 0 && c.signum() >= 0);
      debit = debit.add(d);
      credit = credit.add(c);
      if ("5500".equals(l.getAccountCode())) {
        roundOff = roundOff.add(d).subtract(c);
      }
    }
    assertEquals(0, debit.compareTo(credit), "the entry balances");
    assertEquals(0, roundOff.compareTo(t.rounding()), "rounding sits on 5500");
  }

  private static PayrollInput.EmployeeInput employeeInput(String id, YearMonth month, Spec s) {
    String join = month.atDay(Math.min(s.joinDay(), month.lengthOfMonth())).toString();
    Map<String, AttendanceDay> marks = new HashMap<>();
    Map<String, LeaveRequest> leave = new HashMap<>();
    for (int d = 1; d <= month.lengthOfMonth(); d++) {
      LocalDate date = month.atDay(d);
      String ds = date.toString();
      boolean offDay = date.getDayOfWeek() == DayOfWeek.SUNDAY || d == 10;
      int code = s.days().get(d - 1);
      switch (code) {
        case 1 -> marks.put(ds, PayrollTestFixtures.mark(id, ds, AttendanceStatus.PRESENT,
            null, null));
        case 2 -> marks.put(ds, PayrollTestFixtures.mark(id, ds, AttendanceStatus.PRESENT,
            "08:00", "23:30"));
        case 3 -> marks.put(ds, PayrollTestFixtures.mark(id, ds, AttendanceStatus.HALF_DAY,
            null, null));
        case 4 -> marks.put(ds, PayrollTestFixtures.mark(id, ds, AttendanceStatus.ABSENT,
            null, null));
        case 5 -> {
          if (!offDay) {
            leave.put(ds, PayrollTestFixtures.leave(id, ds, PayrollTestFixtures.PAID_TYPE,
                false));
          }
        }
        case 6 -> {
          if (!offDay) {
            leave.put(ds, PayrollTestFixtures.leave(id, ds, PayrollTestFixtures.UNPAID_TYPE,
                false));
          }
        }
        case 7 -> {
          leave.put(ds, PayrollTestFixtures.leave(id, ds, PayrollTestFixtures.PAID_TYPE, true));
          marks.put(ds, PayrollTestFixtures.mark(id, ds, AttendanceStatus.PRESENT, null, null));
        }
        default -> {
          // unmarked
        }
      }
    }
    List<PayrollLine.ManualItem> manual = new ArrayList<>();
    if (s.bonus().signum() > 0) {
      manual.add(new PayrollLine.ManualItem(ManualItemKind.BONUS, "bonus", s.bonus()));
    }
    if (s.deduction().signum() > 0) {
      manual.add(new PayrollLine.ManualItem(ManualItemKind.OTHER_DEDUCTION, null, s.deduction()));
    }
    List<PayrollInput.AdvanceBalance> advances = s.advanceOutstanding().signum() > 0
        ? List.of(new PayrollInput.AdvanceBalance("adv-" + id, "2024-12-01",
            new BigDecimal("1000"), s.advanceOutstanding()))
        : List.of();
    return new PayrollInput.EmployeeInput(PayrollTestFixtures.employee(id,
        s.daily() ? PayType.DAILY : PayType.MONTHLY, s.rate().toPlainString(), join, null),
        marks, leave, manual, null, advances);
  }

  @Provide
  Arbitrary<YearMonth> months() {
    return Arbitraries.integers().between(1, 12).map(m -> YearMonth.of(2025, m));
  }

  @Provide
  Arbitrary<PerDayBasis> bases() {
    return Arbitraries.of(PerDayBasis.class);
  }

  @Provide
  Arbitrary<List<Spec>> specs() {
    Arbitrary<BigDecimal> smallMoney = Arbitraries.bigDecimals()
        .between(BigDecimal.ZERO, new BigDecimal("3000")).ofScale(2);
    Arbitrary<Spec> spec = Combinators.combine(
        Arbitraries.of(true, false),
        Arbitraries.bigDecimals().between(new BigDecimal("100"), new BigDecimal("200000"))
            .ofScale(2),
        Arbitraries.integers().between(0, 7).list().ofSize(31),
        smallMoney,
        smallMoney,
        smallMoney,
        // about half the employees are there the whole month (join day 1)
        Arbitraries.integers().between(-30, 31).map(d -> Math.max(1, d)))
        .as(Spec::new);
    return spec.list().ofMinSize(1).ofMaxSize(3);
  }
}
