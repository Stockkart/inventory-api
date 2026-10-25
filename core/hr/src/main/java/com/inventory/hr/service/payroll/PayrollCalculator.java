package com.inventory.hr.service.payroll;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.DeductionKind;
import com.inventory.hr.domain.model.enums.EarningKind;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import com.inventory.hr.service.DayStatusResolver;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.HrMoneyUtils;
import com.inventory.hr.utils.SalaryRevisionUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pay of one employee for one month (design "Payroll calculation", R7.3, R8.3–R8.10, R8.15,
 * R14.2). Pure: works only on a {@link PayrollInput} snapshot, so it is property-tested.
 *
 * <p>Rounding: every earning and deduction is one {@code BigDecimal} expression that multiplies
 * first and divides once, rounded once to paise (scale 2, HALF_UP). Gross and net are exact sums
 * of those paise amounts; the only rupee rounding is the net (its own rounding line).
 *
 * <p>Paid days (monthly salary): present + ½ × half days + paid leave + week-offs + holidays. With
 * FIXED_30 / FIXED_26 (decision 2) an employee employed the whole month gets basis − loss-of-pay
 * days (absent + unpaid leave + ½ × half days + unmarked), never below 0; one who joined or left in
 * the month gets the counted paid days, at most the basis. Unmarked days are unpaid in a draft for
 * every basis; finalise refuses a month that still has any.
 *
 * <p>A month not over yet (run B7): a day after {@link PayrollInput#today()} with nothing
 * recorded is "not yet", not unmarked — it is left out of {@code dayCounts.unmarked} and the
 * unmarked dates, and it is not paid, so a current-month DRAFT's paid days are a preview of the
 * days so far (plus off days and approved leave already on the calendar). Finalise refuses a
 * month that is not over (run B6), so a finalised line never has such days and its maths is
 * unchanged.
 *
 * <p>Off days (R5.5): a derived holiday or week-off counts as that off day even when a mark is
 * stored on it, and a PRESENT / HALF_DAY mark there adds 1 / 0.5 to {@code offDayWorked} instead
 * of present / half days. Daily wage: paid days = present + ½ × half days + paid leave + holidays
 * + offDayWorked (unworked week-offs unpaid). Monthly: the off day stays paid and, with overtime
 * on, offDayWorked × rate ÷ basis is added as "Work on off days".
 */
public final class PayrollCalculator {

  /** No salary revision applies in the period: the line has zero pay. */
  public static final String NO_SALARY = "NO_SALARY";
  /** Other deductions are more than gross pay: the reviewer must fix the adjustments. */
  public static final String DEDUCTIONS_EXCEED_PAY = "DEDUCTIONS_EXCEED_PAY";

  static final BigDecimal HALF = new BigDecimal("0.5");

  private PayrollCalculator() {}

  /** The computed line (no ids or payment fields) and the dates still unmarked. */
  public record Result(PayrollLine line, List<String> unmarkedDates) {}

  public static Result calculate(PayrollInput input, PayrollInput.EmployeeInput in) {
    Employee employee = in.employee();
    YearMonth month = input.period();
    String period = HrDateUtils.format(month.atDay(1)).substring(0, 7);
    HrSettings settings = input.settings();
    Employee.SalaryRevision revision =
        SalaryRevisionUtils.revisionFor(employee.getSalaryRevisions(), period);
    List<String> warnings = new ArrayList<>();
    if (revision == null || revision.getAmount() == null) {
      warnings.add(NO_SALARY);
    }
    BigDecimal rate = revision != null ? HrMoneyUtils.nz(revision.getAmount()) : BigDecimal.ZERO;
    PayType payType = revision != null && revision.getPayType() != null
        ? revision.getPayType() : PayType.MONTHLY;

    Counts c = countDays(input, in);
    int basis = basisDays(settings.getPerDayBasis(), month);
    boolean wholeMonth = c.employed == month.lengthOfMonth();
    BigDecimal halfDays = HALF.multiply(BigDecimal.valueOf(c.halfDays));
    BigDecimal paidDays;
    if (payType == PayType.DAILY) {
      // daily wage: unworked week-offs are not paid; a holiday is paid, and work on any off day
      // (holiday or week-off) is paid on top of that (R5.5)
      paidDays = c.present.add(halfDays).add(c.paidLeave).add(c.holiday).add(c.offDayWorked);
    } else {
      // off days are paid even when a mark sits on them; their work is offDayWorked, not present
      BigDecimal counted = c.present.add(halfDays).add(c.paidLeave).add(c.weekOff)
          .add(c.holiday);
      if (settings.getPerDayBasis() == null
          || settings.getPerDayBasis() == PerDayBasis.CALENDAR_DAYS) {
        paidDays = counted;
      } else if (wholeMonth) {
        // marks on off days never reach absent or halfDays, so they never create loss of pay;
        // days not yet happened are not paid in the preview either (run B7)
        BigDecimal lossOfPay = c.absent.add(c.unpaidLeave).add(halfDays).add(c.unmarked)
            .add(c.notYet);
        paidDays = BigDecimal.valueOf(basis).subtract(lossOfPay).max(BigDecimal.ZERO);
      } else {
        paidDays = counted.min(BigDecimal.valueOf(basis));
      }
    }

    Integer workingMinutes = workingMinutes(employee, settings);
    boolean overtimeOn = Boolean.TRUE.equals(settings.getOvertimeEnabled());
    BigDecimal multiplier = HrMoneyUtils.nz(settings.getOvertimeMultiplier());
    BigDecimal basisBd = BigDecimal.valueOf(basis);

    List<PayrollLine.EarningItem> earnings = new ArrayList<>();
    BigDecimal base;
    BigDecimal overtime = BigDecimal.ZERO;
    BigDecimal offDay = BigDecimal.ZERO;
    if (payType == PayType.DAILY) {
      base = HrMoneyUtils.paise(rate.multiply(paidDays));
      if (overtimeOn && workingMinutes != null && c.overtimeMinutes > 0) {
        // rate × otMinutes × multiplier ÷ workingMinutes: one division
        overtime = rate.multiply(BigDecimal.valueOf(c.overtimeMinutes)).multiply(multiplier)
            .divide(BigDecimal.valueOf(workingMinutes), 2, RoundingMode.HALF_UP);
      }
    } else {
      // rate × paidDays ÷ basisDays: e.g. 14,500 × 28 ÷ 30 = 13,533.33
      base = rate.multiply(paidDays).divide(basisBd, 2, RoundingMode.HALF_UP);
      if (overtimeOn && workingMinutes != null && c.overtimeMinutes > 0) {
        overtime = rate.multiply(BigDecimal.valueOf(c.overtimeMinutes)).multiply(multiplier)
            .divide(basisBd.multiply(BigDecimal.valueOf(workingMinutes)), 2,
                RoundingMode.HALF_UP);
      }
      if (overtimeOn && c.offDayWorked.signum() > 0) {
        // a day's pay for each day worked on a week-off or holiday, on top of the paid off day
        // itself (design "Off-day work"); a half day worked there counts 0.5
        offDay = rate.multiply(c.offDayWorked)
            .divide(basisBd, 2, RoundingMode.HALF_UP);
      }
    }
    earnings.add(new PayrollLine.EarningItem(EarningKind.BASE, "Base pay", base));
    if (overtime.signum() > 0) {
      earnings.add(new PayrollLine.EarningItem(EarningKind.OVERTIME, "Overtime", overtime));
    }
    if (offDay.signum() > 0) {
      earnings.add(new PayrollLine.EarningItem(EarningKind.OFF_DAY_WORK, "Work on off days",
          offDay));
    }
    List<PayrollLine.DeductionItem> deductions = new ArrayList<>();
    BigDecimal otherDeductions = BigDecimal.ZERO;
    List<PayrollLine.ManualItem> manual = in.manualItems() != null ? in.manualItems() : List.of();
    for (PayrollLine.ManualItem m : manual) {
      BigDecimal amount = HrMoneyUtils.paise(HrMoneyUtils.nz(m.getAmount()));
      if (m.getKind() == ManualItemKind.BONUS) {
        earnings.add(new PayrollLine.EarningItem(EarningKind.BONUS, label(m.getNote(), "Bonus"),
            amount));
      } else if (m.getKind() == ManualItemKind.OTHER_DEDUCTION) {
        deductions.add(new PayrollLine.DeductionItem(DeductionKind.OTHER,
            label(m.getNote(), "Other deduction"), amount));
        otherDeductions = otherDeductions.add(amount);
      }
    }
    BigDecimal gross = BigDecimal.ZERO;
    for (PayrollLine.EarningItem e : earnings) {
      gross = gross.add(e.getAmount());
    }

    // the advance deduction can only take what is left after the other deductions (R7.3)
    BigDecimal room = gross.subtract(otherDeductions).max(BigDecimal.ZERO);
    List<PayrollInput.AdvanceBalance> advances =
        in.advances() != null ? in.advances() : List.of();
    Allocation allocation = allocate(advances, in.advanceOverride(), room);
    if (allocation.total().signum() > 0) {
      deductions.add(new PayrollLine.DeductionItem(DeductionKind.ADVANCE, "Advance recovery",
          allocation.total()));
    }
    BigDecimal totalDeductions = otherDeductions.add(allocation.total());
    BigDecimal netBeforeRounding = gross.subtract(totalDeductions);
    if (netBeforeRounding.signum() < 0) {
      warnings.add(DEDUCTIONS_EXCEED_PAY);
    }
    // the only rupee rounding (R8.15); |rounding| ≤ 0.50
    BigDecimal net = netBeforeRounding.setScale(0, RoundingMode.HALF_UP).setScale(2);
    BigDecimal rounding = net.subtract(netBeforeRounding);

    PayrollLine line = new PayrollLine();
    line.setEmployeeId(employee.getId());
    line.setPeriod(period);
    line.setEmployeeSnapshot(new PayrollLine.EmployeeSnapshot(employee.getName(),
        employee.getCode(), employee.getDesignation(), employee.getJoinDate(),
        employee.getExitDate(), revision != null ? revision.getPayType() : null,
        revision != null ? revision.getAmount() : null, workingMinutes));
    line.setDayCounts(new PayrollLine.DayCounts(c.employed, c.present, c.halfDays, c.paidLeave,
        c.unpaidLeave, c.absent, c.weekOff, c.holiday, c.offDayWorked, c.unmarked, paidDays,
        basis));
    line.setOvertimeMinutes(c.overtimeMinutes);
    line.setEarnings(earnings);
    line.setDeductions(deductions);
    line.setManualItems(new ArrayList<>(manual));
    line.setAdvanceOverride(in.advanceOverride());
    line.setAdvanceRecoveries(allocation.recoveries());
    BigDecimal owed = BigDecimal.ZERO;
    for (PayrollInput.AdvanceBalance a : advances) {
      owed = owed.add(HrMoneyUtils.nz(a.outstanding()));
    }
    line.setAdvanceOutstandingAfter(advances.isEmpty() ? null : owed.subtract(allocation.total()));
    line.setGross(gross);
    line.setTotalDeductions(totalDeductions);
    line.setNetBeforeRounding(netBeforeRounding);
    line.setRounding(rounding);
    line.setNet(net);
    line.setWarnings(warnings);
    return new Result(line, c.unmarkedDates);
  }

  // ---- advance recovery ------------------------------------------------------------------------

  /** What recovery takes from each advance this month, and the total. */
  public record Allocation(List<PayrollLine.AdvanceRecovery> recoveries, BigDecimal total) {}

  /**
   * Advance recovery (R7.3, R7.4, Property 5). Advances are taken oldest first (FIFO by date,
   * then id). Automatic: each gives min(monthlyDeduction, outstanding). With an override the
   * month's total is the override instead, still taken oldest first. Either way the total is
   * capped at {@code room} (gross − other deductions) and no advance gives more than it owes.
   */
  public static Allocation allocate(List<PayrollInput.AdvanceBalance> advances,
      BigDecimal override, BigDecimal room) {
    List<PayrollInput.AdvanceBalance> fifo = new ArrayList<>(advances);
    fifo.sort(Comparator.comparing(PayrollInput.AdvanceBalance::date)
        .thenComparing(PayrollInput.AdvanceBalance::advanceId));
    BigDecimal remaining = room.max(BigDecimal.ZERO);
    if (override != null) {
      remaining = remaining.min(override.max(BigDecimal.ZERO));
    }
    List<PayrollLine.AdvanceRecovery> recoveries = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (PayrollInput.AdvanceBalance a : fifo) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal owed = HrMoneyUtils.nz(a.outstanding()).max(BigDecimal.ZERO);
      BigDecimal take = owed.min(remaining);
      if (override == null) {
        take = take.min(HrMoneyUtils.nz(a.monthlyDeduction()).max(BigDecimal.ZERO));
      }
      if (take.signum() > 0) {
        recoveries.add(new PayrollLine.AdvanceRecovery(a.advanceId(), take));
        total = total.add(take);
        remaining = remaining.subtract(take);
      }
    }
    return new Allocation(recoveries, total);
  }

  // ---- days ------------------------------------------------------------------------------------

  /** CALENDAR_DAYS = days in the month, FIXED_30 = 30, FIXED_26 = 26. */
  public static int basisDays(PerDayBasis basis, YearMonth month) {
    if (basis == PerDayBasis.FIXED_30) {
      return 30;
    }
    if (basis == PerDayBasis.FIXED_26) {
      return 26;
    }
    return month.lengthOfMonth();
  }

  /** Minutes of the employee's working day (own hours, else the shop's); null when unusable. */
  static Integer workingMinutes(Employee employee, HrSettings settings) {
    LocalTime start = HrDateUtils.parseTime(employee.getWorkStart() != null
        ? employee.getWorkStart() : settings.getWorkStart());
    LocalTime end = HrDateUtils.parseTime(employee.getWorkEnd() != null
        ? employee.getWorkEnd() : settings.getWorkEnd());
    if (start == null || end == null || !end.isAfter(start)) {
      return null;
    }
    return (int) Duration.between(start, end).toMinutes();
  }

  private static final class Counts {
    int employed;
    BigDecimal present = BigDecimal.ZERO;
    int halfDays;
    BigDecimal paidLeave = BigDecimal.ZERO;
    BigDecimal unpaidLeave = BigDecimal.ZERO;
    BigDecimal absent = BigDecimal.ZERO;
    BigDecimal weekOff = BigDecimal.ZERO;
    BigDecimal holiday = BigDecimal.ZERO;
    /** Work on a derived off day: PRESENT 1, HALF_DAY 0.5 (R5.5). */
    BigDecimal offDayWorked = BigDecimal.ZERO;
    BigDecimal unmarked = BigDecimal.ZERO;
    /**
     * Days after {@link PayrollInput#today()} with nothing recorded (and the unrecorded half of a
     * future half-day leave): not unmarked, and not paid either (run B7).
     */
    BigDecimal notYet = BigDecimal.ZERO;
    int overtimeMinutes;
    final List<String> unmarkedDates = new ArrayList<>();
  }

  /** Resolves every day of the month through {@link DayStatusResolver} and counts the statuses. */
  private static Counts countDays(PayrollInput input, PayrollInput.EmployeeInput in) {
    Counts c = new Counts();
    YearMonth month = input.period();
    for (LocalDate date = month.atDay(1); !date.isAfter(month.atEndOfMonth());
        date = date.plusDays(1)) {
      String d = HrDateUtils.format(date);
      boolean holiday = input.holidays().contains(d);
      LeaveRequest leave = in.leave().get(d);
      boolean future = input.today() != null && date.isAfter(input.today());
      DayStatusResolver.Resolution r = DayStatusResolver.resolve(date, in.employee(),
          in.marks().get(d), leave, holiday, input.settings());
      if (r.status() == DayStatus.NOT_EMPLOYED) {
        continue;
      }
      c.employed++;
      c.overtimeMinutes += r.overtimeMinutes();
      // A derived off day (shop holiday or weekly off) is decided from the calendar, not from
      // the stored mark the resolver shows: the off day itself stays paid whatever is stored,
      // and a PRESENT/HALF_DAY mark on it is extra work (R5.5). A stored ABSENT there is just
      // the off day. Leave never covers off days (resolver), so only MARK reaches this branch.
      boolean offDay = holiday || DayStatusResolver.isWeekOff(date, input.settings());
      if (offDay && r.source() == DayStatusResolver.Source.MARK) {
        // a stored HOLIDAY override on a weekly off is still paid as a holiday (daily wage too)
        if (holiday || r.status() == DayStatus.HOLIDAY) {
          c.holiday = c.holiday.add(BigDecimal.ONE);
        } else {
          c.weekOff = c.weekOff.add(BigDecimal.ONE);
        }
        if (r.status() == DayStatus.PRESENT) {
          c.offDayWorked = c.offDayWorked.add(BigDecimal.ONE);
        } else if (r.status() == DayStatus.HALF_DAY) {
          c.offDayWorked = c.offDayWorked.add(HALF);
        }
        continue;
      }
      switch (r.status()) {
        case PRESENT -> c.present = c.present.add(BigDecimal.ONE);
        case HALF_DAY -> c.halfDays++;
        case ABSENT -> c.absent = c.absent.add(BigDecimal.ONE);
        case WEEK_OFF -> c.weekOff = c.weekOff.add(BigDecimal.ONE);
        case HOLIDAY -> c.holiday = c.holiday.add(BigDecimal.ONE);
        case UNMARKED -> {
          if (future) {
            // not yet: nothing to mark, so it is neither unmarked nor paid (run B7)
            c.notYet = c.notYet.add(BigDecimal.ONE);
          } else {
            c.unmarked = c.unmarked.add(BigDecimal.ONE);
            c.unmarkedDates.add(d);
          }
        }
        case LEAVE -> {
          boolean paid = leave != null
              && Boolean.TRUE.equals(input.leaveTypePaid().get(leave.getLeaveTypeId()));
          if (paid) {
            c.paidLeave = c.paidLeave.add(r.leaveDays());
          } else {
            c.unpaidLeave = c.unpaidLeave.add(r.leaveDays());
          }
          if (r.halfDayLeave()) {
            addOtherHalf(c, r.otherHalf(), d, future);
          }
        }
        default -> {
          // NOT_EMPLOYED handled above
        }
      }
    }
    return c;
  }

  /**
   * The non-leave half of a half-day leave counts as half a day of its own status; an unmarked
   * half after today is not yet (run B7).
   */
  private static void addOtherHalf(Counts c, DayStatus other, String date, boolean future) {
    if (other == null) {
      other = DayStatus.UNMARKED;
    }
    switch (other) {
      case PRESENT -> c.present = c.present.add(HALF);
      case ABSENT -> c.absent = c.absent.add(HALF);
      case WEEK_OFF -> c.weekOff = c.weekOff.add(HALF);
      case HOLIDAY -> c.holiday = c.holiday.add(HALF);
      default -> {
        if (future) {
          c.notYet = c.notYet.add(HALF);
        } else {
          c.unmarked = c.unmarked.add(HALF);
          c.unmarkedDates.add(date);
        }
      }
    }
  }

  private static String label(String note, String fallback) {
    return note != null && !note.isBlank() ? note.trim() : fallback;
  }
}
