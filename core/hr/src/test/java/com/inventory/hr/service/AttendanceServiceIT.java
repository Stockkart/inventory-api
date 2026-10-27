package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Attendance against a real MongoDB (R5, R14.1, design Property 12). Runs only with {@code
 * -Dstockkart.test.mongo.uri=…}; every test uses its own shop, so order does not matter.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class AttendanceServiceIT {

  private static final String USER = "owner-1";
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("attendance");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static SaveAttendanceDaysRequest.Row row(Employee e, String date, String status) {
    return new SaveAttendanceDaysRequest.Row(e.getId(), date, status, null, null, null);
  }

  private static SaveAttendanceDaysRequest save(SaveAttendanceDaysRequest.Row... rows) {
    return new SaveAttendanceDaysRequest(new ArrayList<>(List.of(rows)));
  }

  private static long count(Class<?> type, String shopId) {
    return it.template.count(Query.query(Criteria.where("shopId").is(shopId)), type);
  }

  private static List<HrAuditEvent> audit(String shopId) {
    return it.template.find(Query.query(Criteria.where("shopId").is(shopId)), HrAuditEvent.class);
  }

  @Test
  void bulkSaveUpsertsOnceAndAuditsFirstMarksAsOneSummary() {
    String shop = "shop-save";
    Employee a = it.employee(shop, "EMP-001", "Asha", null);
    Employee b = it.employee(shop, "EMP-002", "Bala", null);

    it.attendance.saveDays(USER, shop, save(row(a, "2025-04-07", "PRESENT"),
        row(b, "2025-04-07", "ABSENT"), row(a, "2025-04-08", "HALF_DAY")));
    assertEquals(3, count(AttendanceDay.class, shop));
    assertEquals(1, audit(shop).size());
    assertEquals(HrAuditAction.ATTENDANCE_BULK_SET, audit(shop).get(0).getAction());

    // same rows again plus one change: only the change is written and audited with before/after
    it.attendance.saveDays(USER, shop, save(row(a, "2025-04-07", "PRESENT"),
        row(b, "2025-04-07", "PRESENT")));
    assertEquals(3, count(AttendanceDay.class, shop));
    List<HrAuditEvent> events = audit(shop);
    assertEquals(2, events.size());
    HrAuditEvent change = events.stream()
        .filter(e -> e.getAction() == HrAuditAction.ATTENDANCE_SET).findFirst().orElseThrow();
    assertEquals("ABSENT", change.getBefore().get("status"));
    assertEquals("PRESENT", change.getAfter().get("status"));
    assertEquals(b.getId(), change.getEmployeeId());

    AttendanceDay stored = it.template.findOne(Query.query(Criteria.where("shopId").is(shop)
        .and("employeeId").is(b.getId()).and("date").is("2025-04-07")), AttendanceDay.class);
    assertEquals(AttendanceStatus.PRESENT, stored.getStatus());
    assertEquals(USER, stored.getMarkedByUserId());
    assertEquals("2025-04", stored.getPeriod());

    var history = it.attendance.history(USER, shop, b.getId(), "2025-04-07");
    assertEquals(1, history.getEvents().size());
    assertEquals(USER, history.getMarkedByUserId());
  }

  @Test
  void aLockedMonthRejectsSavesAndMarkAll() {
    String shop = "shop-lock";
    Employee a = it.employee(shop, "EMP-001", "Asha", null);
    PayrollRun run = new PayrollRun();
    run.setShopId(shop);
    run.setPeriod("2025-04");
    run.setStatus(PayrollRunStatus.FINALISED);
    it.template.insert(run);

    BaseException e = assertThrows(BaseException.class, () -> it.attendance.saveDays(USER, shop,
        save(row(a, "2025-04-07", "PRESENT"))));
    assertEquals(ErrorCode.HR_PERIOD_LOCKED, e.getErrorCode());
    e = assertThrows(BaseException.class, () -> it.attendance.markUnmarkedPresent(USER, shop,
        new MarkUnmarkedPresentRequest(new ArrayList<>(List.of("2025-04-07")), null)));
    assertEquals(ErrorCode.HR_PERIOD_LOCKED, e.getErrorCode());
    assertEquals(0, count(AttendanceDay.class, shop));
    assertTrue(it.attendance.daily(USER, shop, "2025-04-07").isLocked());
    // May is open
    it.attendance.saveDays(USER, shop, save(row(a, "2025-05-06", "PRESENT")));
    assertEquals(1, count(AttendanceDay.class, shop));
  }

  @Test
  void markAllUnmarkedNeverOverwritesAndIsRepeatable() {
    String shop = "shop-bulk";
    Employee a = it.employee(shop, "EMP-001", "Asha", null);
    Employee b = it.employee(shop, "EMP-002", "Bala", null);
    it.attendance.saveDays(USER, shop, save(row(a, "2025-04-08", "ABSENT")));
    Holiday h = new Holiday();
    h.setShopId(shop);
    h.setDate("2025-04-09");
    h.setYear(2025);
    h.setName("Holiday");
    it.template.insert(h);

    // Sun 6 week-off, Mon 7 and Tue 8 working, Wed 9 holiday
    int marked = it.attendance.markUnmarkedPresent(USER, shop, new MarkUnmarkedPresentRequest(
        new ArrayList<>(List.of("2025-04-06", "2025-04-07", "2025-04-08", "2025-04-09")), null))
        .getMarked();

    assertEquals(3, marked); // a/7, b/7, b/8
    AttendanceDay kept = it.template.findOne(Query.query(Criteria.where("shopId").is(shop)
        .and("employeeId").is(a.getId()).and("date").is("2025-04-08")), AttendanceDay.class);
    assertEquals(AttendanceStatus.ABSENT, kept.getStatus());
    assertEquals(AttendanceSource.MANUAL, kept.getSource());
    assertEquals(0, it.attendance.markUnmarkedPresent(USER, shop, new MarkUnmarkedPresentRequest(
        new ArrayList<>(List.of("2025-04-07", "2025-04-08")), null)).getMarked());

    MonthRegisterResponse month = it.attendance.month(USER, shop, "2025-04", null);
    assertEquals(2, month.getRows().size());
    MonthRegisterResponse.Row asha = month.getRows().get(0);
    assertEquals("Asha", asha.getName());
    assertEquals(DayStatus.HOLIDAY, asha.getCells().get(8).getStatus());
    assertEquals(DayStatus.WEEK_OFF, asha.getCells().get(5).getStatus());
    assertEquals(DayStatus.ABSENT, asha.getCells().get(7).getStatus());
  }

  @Test
  void anotherShopsDataIsNeverReturnedOrWritten() {
    String shopA = "shop-a";
    String shopB = "shop-b";
    Employee a = it.employee(shopA, "EMP-001", "Asha", null);
    Employee b = it.employee(shopB, "EMP-001", "Bala", null);
    it.attendance.saveDays(USER, shopB, save(row(b, "2025-04-07", "PRESENT")));

    DailyRegisterResponse daily = it.attendance.daily(USER, shopA, "2025-04-07");
    assertEquals(List.of(a.getId()), daily.getRows().stream().map(r -> r.getEmployeeId())
        .toList());
    assertEquals(DayStatus.UNMARKED, daily.getRows().get(0).getStatus());
    assertTrue(it.attendance.month(USER, shopA, "2025-04", b.getId()).getRows().isEmpty());

    assertThrows(ResourceNotFoundException.class, () -> it.attendance.saveDays(USER, shopA,
        save(row(b, "2025-04-08", "PRESENT"))));
    assertThrows(ResourceNotFoundException.class,
        () -> it.attendance.history(USER, shopA, b.getId(), "2025-04-07"));
    assertEquals(1, count(AttendanceDay.class, shopB));
    assertEquals(0, count(AttendanceDay.class, shopA));
  }
}
