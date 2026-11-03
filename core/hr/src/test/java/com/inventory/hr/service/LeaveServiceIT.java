package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.model.enums.LeaveSystemDefault;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.rest.dto.request.RecordLeaveRequest;
import com.inventory.hr.rest.dto.request.RequestLeaveRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.LeaveBalanceRow;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Leave against a real MongoDB (R6, D4, design Properties 3, 4 and 12): the overlay and mark
 * clean-up, the balance aggregation, the month lock and shop isolation. Runs only with {@code
 * -Dstockkart.test.mongo.uri=…}; each test has its own shop.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class LeaveServiceIT {

  private static final String OWNER = "owner-1";
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("leave");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static String casual(String shop) {
    return it.types.ensureDefaults(shop).stream()
        .filter(t -> t.getSystemDefault() == LeaveSystemDefault.CASUAL)
        .map(LeaveType::getId).findFirst().orElseThrow();
  }

  private static void mark(String shop, Employee e, String... dates) {
    List<SaveAttendanceDaysRequest.Row> rows = new ArrayList<>();
    for (String d : dates) {
      rows.add(new SaveAttendanceDaysRequest.Row(e.getId(), d, "PRESENT", null, null, null));
    }
    it.attendance.saveDays(OWNER, shop, new SaveAttendanceDaysRequest(rows));
  }

  private static long marks(String shop, Employee e) {
    return it.template.count(Query.query(Criteria.where("shopId").is(shop).and("employeeId")
        .is(e.getId())), AttendanceDay.class);
  }

  private static BigDecimal used(String shop, String typeId, Employee e) {
    for (LeaveBalanceRow row : it.balances.list(OWNER, shop, "2025")) {
      if (row.getEmployeeId().equals(e.getId())) {
        return row.getBalances().stream().filter(b -> b.getLeaveTypeId().equals(typeId))
            .findFirst().orElseThrow().getUsed();
      }
    }
    throw new AssertionError("employee missing");
  }

  @Test
  void approveOverlaysTheRegisterAndCleansUpWorkingDayMarksOnly() {
    String shop = "shop-overlay";
    String casual = casual(shop);
    Employee e = it.employee(shop, "EMP-001", "Asha", "login-asha");
    // Sat 5, Sun 6 (worked on the week-off), Mon 7 marked Present before the leave
    mark(shop, e, "2025-04-05", "2025-04-06", "2025-04-07");

    LeaveRequestResponse pending = it.leave.requestForSelf(shop, "login-asha",
        new RequestLeaveRequest(casual, "2025-04-05", "2025-04-07", null, "wedding"));
    assertEquals(0, pending.getDays().compareTo(new BigDecimal("2")));
    assertEquals(1, it.notifications.listForCaller(OWNER, shop, null).getItems().size());

    LeaveRequestResponse approved = it.leave.approve(OWNER, shop, pending.getId(), "enjoy");
    assertEquals(LeaveRequestStatus.APPROVED, approved.getStatus());
    // the Sunday mark (work on an off day) stays, the two working-day marks are gone
    assertEquals(1, marks(shop, e));
    assertEquals(DayStatus.LEAVE,
        it.attendance.daily(OWNER, shop, "2025-04-07").getRows().get(0).getStatus());
    assertEquals(DayStatus.PRESENT,
        it.attendance.daily(OWNER, shop, "2025-04-06").getRows().get(0).getStatus());
    // marking a leave day is refused, the requester got a notification
    BaseException blocked = assertThrows(BaseException.class, () -> mark(shop, e, "2025-04-07"));
    assertEquals(ErrorCode.HR_DAY_HAS_LEAVE, blocked.getErrorCode());
    assertTrue(it.notifications.listForCaller("login-asha", shop, null).getItems().stream()
        .anyMatch(n -> n.getType() == HrNotificationType.LEAVE_APPROVED
            && n.getText().contains("2025-04-05 to 2025-04-07")));
    // approving twice is a conflict
    assertEquals(ErrorCode.HR_LEAVE_STATE_CONFLICT, assertThrows(BaseException.class,
        () -> it.leave.approve(OWNER, shop, pending.getId(), null)).getErrorCode());
  }

  @Test
  void theBalanceAggregationRoundTripsThroughApproveAndCancel() {
    String shop = "shop-balance";
    String casual = casual(shop);
    Employee e = it.employee(shop, "EMP-001", "Asha", null);
    Employee other = it.employee(shop, "EMP-002", "Bala", null);
    BigDecimal before = used(shop, casual, e);

    LeaveRequestResponse rec = it.leave.record(OWNER, shop,
        new RecordLeaveRequest(e.getId(), casual, "2025-04-08", "2025-04-09", null, null));
    it.leave.record(OWNER, shop,
        new RecordLeaveRequest(other.getId(), casual, "2025-04-08", "2025-04-08", true, null));
    assertEquals(0, used(shop, casual, e).compareTo(before.add(new BigDecimal("2"))));
    assertEquals(0, used(shop, casual, other).compareTo(new BigDecimal("0.5")));

    // overlap with the approved request is refused
    assertEquals(ErrorCode.HR_LEAVE_OVERLAP, assertThrows(BaseException.class,
        () -> it.leave.record(OWNER, shop, new RecordLeaveRequest(e.getId(), casual,
            "2025-04-09", "2025-04-10", null, null))).getErrorCode());

    it.leave.cancel(OWNER, shop, rec.getId(), "booked by mistake");
    assertEquals(0, used(shop, casual, e).compareTo(before));
    assertEquals(DayStatus.UNMARKED,
        it.attendance.daily(OWNER, shop, "2025-04-08").getRows().get(0).getStatus());

    // more than the 12 days of casual leave is only possible as unpaid
    BaseException over = assertThrows(BaseException.class, () -> it.leave.record(OWNER, shop,
        new RecordLeaveRequest(e.getId(), casual, "2025-05-01", "2025-05-31", null, null)));
    assertEquals(ErrorCode.HR_LEAVE_BALANCE_EXCEEDED, over.getErrorCode());
  }

  @Test
  void aLockedMonthRejectsApproveRecordAndCancel() {
    String shop = "shop-leave-lock";
    String casual = casual(shop);
    Employee e = it.employee(shop, "EMP-001", "Asha", "login-asha2");
    LeaveRequestResponse pending = it.leave.requestForSelf(shop, "login-asha2",
        new RequestLeaveRequest(casual, "2025-04-08", "2025-04-08", null, null));
    LeaveRequestResponse recorded = it.leave.record(OWNER, shop,
        new RecordLeaveRequest(e.getId(), casual, "2025-04-10", "2025-04-10", null, null));
    PayrollRun run = new PayrollRun();
    run.setShopId(shop);
    run.setPeriod("2025-04");
    run.setStatus(PayrollRunStatus.FINALISING);
    it.template.insert(run);

    assertEquals(ErrorCode.HR_PERIOD_LOCKED, assertThrows(BaseException.class,
        () -> it.leave.approve(OWNER, shop, pending.getId(), null)).getErrorCode());
    assertEquals(ErrorCode.HR_PERIOD_LOCKED, assertThrows(BaseException.class,
        () -> it.leave.cancel(OWNER, shop, recorded.getId(), null)).getErrorCode());
    assertEquals(ErrorCode.HR_PERIOD_LOCKED, assertThrows(BaseException.class,
        () -> it.leave.record(OWNER, shop, new RecordLeaveRequest(e.getId(), casual,
            "2025-04-14", "2025-04-14", null, null))).getErrorCode());
    // a pending request touches nothing, so it can still be withdrawn or rejected
    assertEquals(LeaveRequestStatus.CANCELLED,
        it.leave.cancelOwn(shop, "login-asha2", pending.getId(), null).getStatus());
  }

  @Test
  void anotherShopsRequestsAreNeverListedOrDecided() {
    String shopA = "shop-leave-a";
    String shopB = "shop-leave-b";
    Employee b = it.employee(shopB, "EMP-001", "Bala", null);
    it.employee(shopA, "EMP-001", "Asha", null);
    LeaveRequestResponse inB = it.leave.record(OWNER, shopB,
        new RecordLeaveRequest(b.getId(), casual(shopB), "2025-04-08", "2025-04-08", null,
            null));

    assertTrue(it.leave.list(OWNER, shopA, null, null, null, null).getItems().isEmpty());
    assertEquals(1, it.leave.list(OWNER, shopB, null, null, null, null).getItems().size());
    assertThrows(ResourceNotFoundException.class,
        () -> it.leave.cancel(OWNER, shopA, inB.getId(), null));
    assertThrows(ResourceNotFoundException.class, () -> it.leave.record(OWNER, shopA,
        new RecordLeaveRequest(b.getId(), casual(shopA), "2025-04-09", "2025-04-09", null,
            null)));
    for (LeaveBalanceRow row : it.balances.list(OWNER, shopA, "2025")) {
      assertTrue(row.getBalances().stream().allMatch(x -> x.getUsed().signum() == 0));
    }
  }
}
