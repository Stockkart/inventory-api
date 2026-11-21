package com.inventory.hr.service.payroll;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builders for payroll calculator tests (T7). */
final class PayrollTestFixtures {

  static final String PAID_TYPE = "casual";
  static final String UNPAID_TYPE = "unpaid";

  private PayrollTestFixtures() {}

  /** Defaults of HrSettingsService: Sunday off, 09:00–21:00, grace 15, half day below 4 h. */
  static HrSettings settings(PerDayBasis basis, boolean overtime) {
    HrSettings s = new HrSettings();
    s.setShopId("shop-1");
    s.setWeeklyOffSchedule(new ArrayList<>(List.of(
        new HrSettings.WeeklyOffEntry("0000-01", List.of(DayOfWeek.SUNDAY)))));
    s.setWorkStart("09:00");
    s.setWorkEnd("21:00");
    s.setGraceMinutes(15);
    s.setHalfDayBelowHours(new BigDecimal("4"));
    s.setOvertimeEnabled(overtime);
    s.setOvertimeMultiplier(new BigDecimal("1.5"));
    s.setPerDayBasis(basis);
    return s;
  }

  static Employee employee(String id, PayType type, String rate, String joinDate,
      String exitDate) {
    Employee e = new Employee();
    e.setId(id);
    e.setCode("EMP-" + id);
    e.setName("Name " + id);
    e.setJoinDate(joinDate);
    e.setExitDate(exitDate);
    e.setStatus(exitDate == null ? EmployeeStatus.ACTIVE : EmployeeStatus.EXITED);
    if (rate != null) {
      e.setSalaryRevisions(new ArrayList<>(List.of(new Employee.SalaryRevision("2024-01", type,
          new BigDecimal(rate), Instant.EPOCH, "u"))));
    }
    return e;
  }

  static AttendanceDay mark(String employeeId, String date, AttendanceStatus status,
      String in, String out) {
    AttendanceDay d = new AttendanceDay();
    d.setEmployeeId(employeeId);
    d.setDate(date);
    d.setPeriod(date.substring(0, 7));
    d.setStatus(status);
    d.setInTime(in);
    d.setOutTime(out);
    return d;
  }

  static LeaveRequest leave(String employeeId, String date, String typeId, boolean halfDay) {
    LeaveRequest l = new LeaveRequest();
    l.setId("leave-" + date);
    l.setEmployeeId(employeeId);
    l.setLeaveTypeId(typeId);
    l.setFromDate(date);
    l.setToDate(date);
    l.setHalfDay(halfDay);
    l.setStatus(LeaveRequestStatus.APPROVED);
    return l;
  }

  static Map<String, Boolean> leaveTypes() {
    Map<String, Boolean> m = new HashMap<>();
    m.put(PAID_TYPE, true);
    m.put(UNPAID_TYPE, false);
    return m;
  }
}
