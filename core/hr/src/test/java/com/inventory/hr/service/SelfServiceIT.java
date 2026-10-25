package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.RequestLeaveRequest;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.rest.dto.response.MyLeaveResponse;
import com.inventory.hr.rest.dto.response.MyPayslipResponse;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Self-service (R1.5, R1.6, R10.5, Property 3): a linked login sees only its own employee,
 * attendance, leave and payslips; another employee's payslip is a 404; a login without an
 * employee gets HR_NOT_LINKED everywhere except notifications.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class SelfServiceIT {

  private static final String OWNER = "owner-1";
  private static final String SHOP = "shop-self";
  private static final String ASHA_LOGIN = "login-asha";
  private static HrMongoItSupport it;
  private static Employee asha;
  private static Employee bala;
  private static PayrollRun april;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("self");
    asha = it.paidEmployee(SHOP, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    bala = it.paidEmployee(SHOP, "EMP-002", "Bala", "2025-01-01", "2025-01", "20000");
    it.template.updateFirst(Query.query(Criteria.where("_id").is(asha.getId())),
        new Update().set("userId", ASHA_LOGIN).set("bankAccountNumber", "123456789012")
            .set("paymentMethod", "BANK"), Employee.class);
    april = it.finalisedRun(SHOP, "2025-04", asha, bala);
    // May stays a draft: not a payslip yet
    it.markMonthPresent(SHOP, asha, YearMonth.of(2025, 5));
    it.markMonthPresent(SHOP, bala, YearMonth.of(2025, 5));
    it.payroll.create(OWNER, SHOP, new CreatePayrollRunRequest("2025-05"));
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static PayrollLine lineOf(Employee e, String runId) {
    return it.template.findOne(Query.query(Criteria.where("shopId").is(SHOP).and("runId")
        .is(runId).and("employeeId").is(e.getId())), PayrollLine.class);
  }

  @Test
  void meShowsOwnPayWithBankDetailsMasked() {
    EmployeeResponse me = it.selfServiceFacade.me(SHOP, ASHA_LOGIN);

    assertEquals(asha.getId(), me.getId());
    assertNotNull(me.getSalaryAmount(), "own pay is shown");
    assertEquals("XXXXXX9012", me.getBankAccountNumber());
  }

  @Test
  void attendanceIsOneRowAndOnlyOwn() {
    MonthRegisterResponse month = it.selfServiceFacade.attendance(SHOP, ASHA_LOGIN, "2025-04");

    assertEquals(1, month.getRows().size());
    assertEquals(asha.getId(), month.getRows().get(0).getEmployeeId());
    assertTrue(month.isLocked());
  }

  @Test
  void leaveRequestsAndBalancesAreOwnAndCancelOfAnotherIsNotFound() {
    String casual = it.types.ensureDefaults(SHOP).stream()
        .filter(t -> Boolean.TRUE.equals(t.getPaid())).map(LeaveType::getId).findFirst()
        .orElseThrow();
    LeaveRequestResponse mine = it.selfServiceFacade.requestLeave(SHOP, ASHA_LOGIN,
        new RequestLeaveRequest(casual, "2025-06-10", "2025-06-10", null, "family"));
    assertNotNull(mine.getLeaveTypeName());
    LeaveRequestResponse balas = it.leave.record(OWNER, SHOP,
        new com.inventory.hr.rest.dto.request.RecordLeaveRequest(bala.getId(), casual,
            "2025-06-11", "2025-06-11", null, null));

    MyLeaveResponse leave = it.selfServiceFacade.leave(SHOP, ASHA_LOGIN, "2025");

    assertEquals(asha.getId(), leave.getBalances().getEmployeeId());
    assertTrue(leave.getRequests().stream().allMatch(r -> asha.getId().equals(r.getEmployeeId())));
    assertTrue(leave.getRequests().stream().anyMatch(r -> r.getId().equals(mine.getId())));
    assertThrows(ResourceNotFoundException.class,
        () -> it.selfServiceFacade.cancelLeave(SHOP, ASHA_LOGIN, balas.getId(), null));
  }

  @Test
  void payslipsAreOwnAndFinalisedOnly() {
    List<MyPayslipResponse> slips = it.selfServiceFacade.payslips(SHOP, ASHA_LOGIN);

    assertEquals(1, slips.size(), "the May draft is not a payslip");
    assertEquals("2025-04", slips.get(0).getPeriod());
    HrFile own = it.selfServiceFacade.payslipPdf(SHOP, ASHA_LOGIN, slips.get(0).getLineId());
    assertTrue(own.bytes().length > 1000);

    String balasLine = lineOf(bala, april.getId()).getId();
    assertThrows(ResourceNotFoundException.class,
        () -> it.selfServiceFacade.payslipPdf(SHOP, ASHA_LOGIN, balasLine));
    String draftLine = it.template.findOne(Query.query(Criteria.where("shopId").is(SHOP)
        .and("period").is("2025-05").and("employeeId").is(asha.getId())), PayrollLine.class)
        .getId();
    assertThrows(ResourceNotFoundException.class,
        () -> it.selfServiceFacade.payslipPdf(SHOP, ASHA_LOGIN, draftLine));
    // a payslip of the same employee id in another shop is never found
    assertThrows(BaseException.class,
        () -> it.selfServiceFacade.payslipPdf("other-shop", ASHA_LOGIN, slips.get(0).getLineId()));
  }

  @Test
  void anAddEditOnlyCallerLinkingOwnLoginIs403() {
    String cashier = "login-cashier";
    EmployeeService addEditOnly = it.employeesAs(
        java.util.EnumSet.of(HrPermission.VIEW_EMPLOYEES, HrPermission.ADD_EDIT_EMPLOYEES), false,
        List.of(new ShopMemberSummaryDto(cashier, "Chandra", "CASHIER", "ACTIVE")));
    Employee target = it.employee(SHOP, "EMP-101", "Devi", null);
    EmployeeResponse current = addEditOnly.get(cashier, SHOP, target.getId());

    BaseException e = assertThrows(BaseException.class, () -> addEditOnly.update(cashier, SHOP,
        target.getId(), saveRequest(current, cashier)));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    assertNull(it.template.findById(target.getId(), Employee.class).getUserId());
    // and the cashier still is not anybody in self-service
    assertEquals(ErrorCode.HR_NOT_LINKED, assertThrows(BaseException.class,
        () -> it.selfServiceFacade.me(SHOP, cashier)).getErrorCode());
  }

  @Test
  void anAddEditOnlyCallerChangingAnotherEmployeesLinkIs403() {
    String cashier = "login-cashier-2";
    EmployeeService addEditOnly = it.employeesAs(
        java.util.EnumSet.of(HrPermission.VIEW_EMPLOYEES, HrPermission.ADD_EDIT_EMPLOYEES), false,
        List.of(new ShopMemberSummaryDto("login-eshwar", "Eshwar", "CASHIER", "ACTIVE"),
            new ShopMemberSummaryDto("login-other", "Other", "CASHIER", "ACTIVE")));
    Employee target = it.employee(SHOP, "EMP-102", "Eshwar", "login-eshwar");
    EmployeeResponse current = addEditOnly.get(cashier, SHOP, target.getId());

    for (String link : java.util.Arrays.asList("login-other", null)) {
      BaseException e = assertThrows(BaseException.class, () -> addEditOnly.update(cashier, SHOP,
          target.getId(), saveRequest(current, link)));
      assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    }
    assertEquals("login-eshwar",
        it.template.findById(target.getId(), Employee.class).getUserId());
    // sending the stored link back is a plain profile edit
    SaveEmployeeRequest same = saveRequest(current, "login-eshwar");
    same.setDesignation("Senior helper");
    assertEquals("login-eshwar",
        addEditOnly.update(cashier, SHOP, target.getId(), same).getUserId());
  }

  @Test
  void theOwnerCanLinkALoginAndItIsAudited() {
    String gita = "login-gita";
    EmployeeService asOwner = it.employeesAs(java.util.EnumSet.allOf(HrPermission.class), true,
        List.of(new ShopMemberSummaryDto(gita, "Gita", "CASHIER", "ACTIVE"),
            new ShopMemberSummaryDto(OWNER, "Owner", "OWNER", "OWNER")));
    Employee target = it.employee(SHOP, "EMP-103", "Gita", null);
    EmployeeResponse current = asOwner.get(OWNER, SHOP, target.getId());

    assertEquals(gita, asOwner.update(OWNER, SHOP, target.getId(), saveRequest(current, gita))
        .getUserId());
    assertEquals(target.getId(), it.selfServiceFacade.me(SHOP, gita).getId());
    HrAuditEvent audit = it.template.findOne(Query.query(Criteria.where("shopId").is(SHOP)
        .and("entityId").is(target.getId()).and("action")
        .is(HrAuditAction.EMPLOYEE_LINK_CHANGED)), HrAuditEvent.class);
    assertNotNull(audit);
    assertEquals("yes", audit.getAfter().get("linked"));
    // the owner may also link their own login
    Employee ownRecord = it.employee(SHOP, "EMP-104", "Owner", null);
    assertEquals(OWNER, asOwner.update(OWNER, SHOP, ownRecord.getId(),
        saveRequest(asOwner.get(OWNER, SHOP, ownRecord.getId()), OWNER)).getUserId());
  }

  @Test
  void aViewSalariesMemberWhoIsNotTheOwnerCannotLinkTheirOwnLogin() {
    String manager = "login-manager";
    EmployeeService viewSalaries = it.employeesAs(java.util.EnumSet.of(
        HrPermission.VIEW_EMPLOYEES, HrPermission.ADD_EDIT_EMPLOYEES, HrPermission.VIEW_SALARIES),
        false, List.of(new ShopMemberSummaryDto(manager, "Mani", "MANAGER", "ACTIVE")));
    Employee target = it.employee(SHOP, "EMP-105", "Hari", null);

    BaseException e = assertThrows(BaseException.class, () -> viewSalaries.update(manager, SHOP,
        target.getId(), saveRequest(viewSalaries.get(manager, SHOP, target.getId()), manager)));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    assertNull(it.template.findById(target.getId(), Employee.class).getUserId());
  }

  /** The PUT body the frontend sends back from a detail read, with the given link. */
  private static SaveEmployeeRequest saveRequest(EmployeeResponse current, String link) {
    SaveEmployeeRequest r = new SaveEmployeeRequest();
    r.setName(current.getName());
    r.setCode(current.getCode());
    r.setJoinDate(current.getJoinDate());
    r.setEmail(current.getEmail());
    r.setRevision(current.getRevision());
    r.setUserId(link);
    return r;
  }

  @Test
  void anUnlinkedLoginIsNotLinkedButStillHasNotifications() {
    for (Runnable call : List.<Runnable>of(
        () -> it.selfServiceFacade.me(SHOP, "login-nobody"),
        () -> it.selfServiceFacade.attendance(SHOP, "login-nobody", "2025-04"),
        () -> it.selfServiceFacade.leave(SHOP, "login-nobody", null),
        () -> it.selfServiceFacade.payslips(SHOP, "login-nobody"))) {
      BaseException e = assertThrows(BaseException.class, call::run);
      assertEquals(ErrorCode.HR_NOT_LINKED, e.getErrorCode());
    }
    assertNotNull(it.selfServiceFacade.notifications(SHOP, "login-nobody", null).getItems());
    assertNull(it.selfServiceFacade.notifications(SHOP, "login-nobody",
        "2030-01-01T00:00:00Z").getItems().stream().findFirst().orElse(null));
  }
}
