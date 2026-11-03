package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.rest.dto.request.RecordLeaveRequest;
import com.inventory.hr.rest.dto.response.SalaryPaymentsResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Shop isolation (R14.1, Property 4): two shops with the same kind of data; every endpoint
 * family called with shop A's {@code shopId} returns only shop A's employees, attendance, leave,
 * advances and payroll, and shop B's ids are 404 (or "not in this run") through shop A.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class HrTenancyIT {

  private static final String OWNER = "owner-1";
  private static final String A = "shop-a";
  private static final String B = "shop-b";
  private static HrMongoItSupport it;
  private static Employee a1;
  private static Employee b1;
  private static PayrollRun runA;
  private static PayrollRun runB;
  private static String advanceB;
  private static String leaveB;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("tenancy");
    a1 = it.paidEmployee(A, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    b1 = it.paidEmployee(B, "EMP-001", "Bala", "2025-01-01", "2025-01", "30000");
    for (String shop : List.of(A, B)) {
      Employee e = A.equals(shop) ? a1 : b1;
      RecordAdvanceRequest adv = new RecordAdvanceRequest();
      adv.setEmployeeId(e.getId());
      adv.setDate("2025-03-03");
      adv.setAmount(new BigDecimal("1000"));
      adv.setMonthlyDeduction(new BigDecimal("500"));
      adv.setPaidBy("CASH");
      String id = it.advances.record(OWNER, shop, adv).getId();
      String casual = it.types.ensureDefaults(shop).stream()
          .filter(t -> Boolean.TRUE.equals(t.getPaid())).map(LeaveType::getId).findFirst()
          .orElseThrow();
      String leave = it.leave.record(OWNER, shop, new RecordLeaveRequest(e.getId(), casual,
          "2025-06-10", "2025-06-10", null, null)).getId();
      if (B.equals(shop)) {
        advanceB = id;
        leaveB = leave;
      }
    }
    runA = it.finalisedRun(A, "2025-04", a1);
    runB = it.finalisedRun(B, "2025-04", b1);
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static Set<String> employeeIds(Stream<String> ids) {
    return ids.collect(Collectors.toSet());
  }

  @Test
  void employeesAndAttendanceStayInTheirShop() {
    assertEquals(Set.of(a1.getId()), employeeIds(it.employees.list(OWNER, A, null, null, null,
        0, 100).getItems().stream().map(r -> r.getId())));
    assertThrows(ResourceNotFoundException.class, () -> it.employees.get(OWNER, A, b1.getId()));
    assertEquals(Set.of(a1.getId()), employeeIds(it.attendance.daily(OWNER, A, "2025-04-07")
        .getRows().stream().map(r -> r.getEmployeeId())));
    assertEquals(Set.of(a1.getId()), employeeIds(it.attendance.month(OWNER, A, "2025-04", null)
        .getRows().stream().map(r -> r.getEmployeeId())));
    assertTrue(it.attendance.month(OWNER, A, "2025-04", b1.getId()).getRows().isEmpty());
  }

  @Test
  void leaveAndAdvancesStayInTheirShop() {
    assertTrue(it.leave.list(OWNER, A, null, null, 0, 100).getItems().stream()
        .allMatch(r -> a1.getId().equals(r.getEmployeeId())));
    assertThrows(ResourceNotFoundException.class,
        () -> it.leave.cancel(OWNER, A, leaveB, null));
    assertEquals(Set.of(a1.getId()), employeeIds(it.balances.list(OWNER, A, "2025", null)
        .stream().map(r -> r.getEmployeeId())));
    assertTrue(it.balances.list(OWNER, A, "2025", b1.getId()).isEmpty());
    assertTrue(it.advances.list(OWNER, A, null).stream()
        .allMatch(r -> a1.getId().equals(r.getEmployeeId())));
    assertThrows(ResourceNotFoundException.class,
        () -> it.advances.cancel(OWNER, A, advanceB, "not mine"));
  }

  @Test
  void payrollPaymentsPayslipsAndRegistersStayInTheirShop() {
    assertEquals(List.of(runA.getId()), it.payroll.list(OWNER, A).stream()
        .map(r -> r.getId()).toList());
    assertThrows(ResourceNotFoundException.class, () -> it.payroll.get(OWNER, A, runB.getId()));
    assertThrows(ResourceNotFoundException.class,
        () -> it.reports.salaryRegister(OWNER, A, runB.getId(), "xlsx"));

    PayrollLine lineB = it.template.findOne(Query.query(Criteria.where("shopId").is(B)),
        PayrollLine.class);
    assertThrows(ResourceNotFoundException.class,
        () -> it.payslips.staffPayslip(OWNER, A, runB.getId(), lineB.getId()));
    assertThrows(ResourceNotFoundException.class,
        () -> it.payslips.staffPayslip(OWNER, A, runA.getId(), lineB.getId()));
    // B's line through A's run is "not in this run", and nothing is paid or posted in B
    SalaryPaymentsResponse r = it.payments.pay(OWNER, A, runA.getId(),
        SalaryPaymentIT.payRequest(List.of(lineB.getId()), false));
    assertEquals(SalaryPaymentsResponse.LineStatus.NOT_FOUND, r.getLines().get(0).getStatus());
    assertFalse(it.template.findById(lineB.getId(), PayrollLine.class).getPaymentSeq() > 0);
    assertThrows(ResourceNotFoundException.class,
        () -> it.payments.undo(OWNER, A, runA.getId(), lineB.getId(), null));
  }
}
