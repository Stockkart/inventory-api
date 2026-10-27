package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.request.SelfCheckInRequest;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.SelfCheckInResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Self check-in against a real MongoDB (R2–R4): punch storage and its indexes, the derived day,
 * manual-wins, re-derive after a cleared mark, the punchCount guard and coordinate privacy. Runs
 * only with {@code -Dstockkart.test.mongo.uri=…}; every test uses its own shop.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class SelfCheckInIT {

  private static final String MANAGER = "manager-1";
  private static final double SHOP_LAT = 12.9716;
  private static final double SHOP_LNG = 77.5946;
  private static final double EMPLOYEE_LAT = 12.971837;
  private static final double EMPLOYEE_LNG = 77.594711;
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("selfcheckin");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static void settings(String shop, OutsideAction action) {
    HrSettings s = new HrSettings();
    s.setShopId(shop);
    // no weekly off today, whatever weekday the test runs on
    s.getWeeklyOffSchedule().add(new HrSettings.WeeklyOffEntry("0000-01",
        List.of(HrDateUtils.today().plusDays(1).getDayOfWeek())));
    s.setAttendanceCapture(new HrSettings.AttendanceCapture(true, action != null, SHOP_LAT,
        SHOP_LNG, 100, action));
    it.template.insert(s);
  }

  private static SelfCheckInRequest near(double accuracy) {
    SelfCheckInRequest r = new SelfCheckInRequest();
    r.setLat(EMPLOYEE_LAT);
    r.setLng(EMPLOYEE_LNG);
    r.setAccuracy(accuracy);
    return r;
  }

  private static String today() {
    return HrDateUtils.format(HrDateUtils.today());
  }

  private static AttendanceDay day(String shop, Employee e) {
    return it.template.findOne(Query.query(Criteria.where("shopId").is(shop)
        .and("employeeId").is(e.getId()).and("date").is(today())), AttendanceDay.class);
  }

  private static long punches(String shop) {
    return it.template.count(Query.query(Criteria.where("shopId").is(shop)), Punch.class);
  }

  /** An earlier punch of today, at least 5 minutes before now (clamped to midnight). */
  private static void earlierPunch(String shop, Employee e) {
    Instant midnight = HrDateUtils.today().atStartOfDay(HrDateUtils.ZONE).toInstant();
    Instant at = Instant.ofEpochSecond(Instant.now().minusSeconds(600).getEpochSecond());
    if (at.isBefore(midnight)) {
      at = midnight;
    }
    Punch p = new Punch();
    p.setShopId(shop);
    p.setEmployeeId(e.getId());
    p.setAt(at);
    p.setLocalDate(today());
    p.setDirection(PunchDirection.IN);
    p.setFlags(new ArrayList<>());
    p.setDedupeKey(SelfCheckInService.dedupeKey(e.getId(), at));
    p.setCreatedAt(at);
    it.template.insert(p);
  }

  @Test
  void aCheckInStoresOnePunchWithDistanceOnlyAndDerivesAPresentDay() {
    String shop = "shop-self";
    settings(shop, OutsideAction.BLOCK);
    Employee e = it.employee(shop, "EMP-001", "Asha", "user-asha");

    SelfCheckInResponse r = it.selfCheckIn.checkIn(shop, "user-asha", near(8.2));

    assertEquals(1, punches(shop));
    Document raw = it.template.getCollection("hr_punches").find(new Document("shopId", shop))
        .first();
    // privacy (R3.6): only distance and accuracy; the coordinates are nowhere in the document
    assertFalse(raw.containsKey("lat") || raw.containsKey("lng"));
    assertFalse(raw.toJson().contains("12.971837") || raw.toJson().contains("77.594711"),
        raw.toJson());
    assertTrue(raw.getInteger("distanceMetres") >= 20 && raw.getInteger("distanceMetres") <= 35);
    assertEquals(9, raw.getInteger("accuracyMetres"));
    assertEquals("S:" + e.getId() + ":" + raw.getDate("at").toInstant().getEpochSecond(),
        raw.getString("dedupeKey"));
    assertEquals(PunchDirection.OUT, r.getNextDirection());

    AttendanceDay d = day(shop, e);
    assertEquals(AttendanceSource.SELF_CHECK_IN, d.getSource());
    assertEquals(AttendanceStatus.PRESENT, d.getStatus());
    assertEquals(r.getTodayIn(), d.getInTime());
    assertNull(d.getOutTime());
    assertEquals(1, d.getPunchCount());
    assertNull(d.getMarkedByUserId());

    // a second tap now is within 5 minutes: stored (or dropped as the same second), not counted
    SelfCheckInResponse again = it.selfCheckIn.checkIn(shop, "user-asha", near(8.2));
    assertEquals(1, again.getPunchCount());
    assertEquals(1, day(shop, e).getPunchCount());
  }

  @Test
  void aLaterPunchIsTheCheckOutAndTheRegisterShowsTheSource() {
    String shop = "shop-out";
    settings(shop, null);
    Employee e = it.employee(shop, "EMP-001", "Bala", "user-bala");
    earlierPunch(shop, e);

    SelfCheckInResponse r = it.selfCheckIn.checkIn(shop, "user-bala", null);

    assertEquals(2, r.getPunchCount());
    assertTrue(r.getTodayOut() != null);
    AttendanceDay d = day(shop, e);
    assertEquals(2, d.getPunchCount());
    assertEquals(r.getTodayOut(), d.getOutTime());
    AttendanceRowResponse row = it.attendance.daily(MANAGER, shop, today()).getRows().get(0);
    assertEquals(AttendanceSource.SELF_CHECK_IN, row.getMarkSource());
    assertFalse(row.isMissingCheckOut());
    assertEquals(DayStatusResolver.Source.MARK, row.getSource());
  }

  @Test
  void aHandMadeMarkIsNeverOverwrittenAndClearingItReDerivesFromPunches() {
    String shop = "shop-manual";
    settings(shop, null);
    Employee e = it.employee(shop, "EMP-001", "Chitra", "user-chitra");
    it.attendance.saveDays(MANAGER, shop, new SaveAttendanceDaysRequest(new ArrayList<>(List.of(
        new SaveAttendanceDaysRequest.Row(e.getId(), today(), "ABSENT", null, null, null)))));

    it.selfCheckIn.checkIn(shop, "user-chitra", null);

    assertEquals(1, punches(shop), "the punch is still stored");
    AttendanceDay manual = day(shop, e);
    assertEquals(AttendanceStatus.ABSENT, manual.getStatus());
    assertEquals(AttendanceSource.MANUAL, manual.getSource());
    assertNull(manual.getPunchCount());

    List<AttendanceRowResponse> rows = it.attendance.saveDays(MANAGER, shop,
        new SaveAttendanceDaysRequest(new ArrayList<>(List.of(
            new SaveAttendanceDaysRequest.Row(e.getId(), today(), "UNMARKED", null, null,
                null)))));

    assertEquals(DayStatus.PRESENT, rows.get(0).getStatus());
    AttendanceDay derived = day(shop, e);
    assertEquals(AttendanceSource.SELF_CHECK_IN, derived.getSource());
    assertEquals(1, derived.getPunchCount());
  }

  @Test
  void anOlderResultNeverOverwritesANewerOne() {
    String shop = "shop-race";
    settings(shop, null);
    Employee e = it.employee(shop, "EMP-001", "Dev", "user-dev");
    HrSettings s = HrSettingsService.withDefaults(null, shop);
    Instant nine = HrDateUtils.today().atStartOfDay(HrDateUtils.ZONE).toInstant()
        .plusSeconds(9 * 3600);
    Punch in = new Punch();
    in.setAt(nine);
    Punch out = new Punch();
    out.setAt(nine.plusSeconds(8 * 3600));
    PunchDayDeriver.Derived two = PunchDayService.derive(List.of(in, out), e, today(), s);
    PunchDayDeriver.Derived one = PunchDayService.derive(List.of(in), e, today(), s);

    assertTrue(it.punchDays.writeDerived(shop, e, today(), s, two) != null);
    assertNull(it.punchDays.writeDerived(shop, e, today(), s, one), "fewer punches: dropped");

    AttendanceDay d = day(shop, e);
    assertEquals(2, d.getPunchCount());
    assertEquals("17:00", d.getOutTime());
  }

  @Test
  void bulkPresentAndSourceLessMarksAreNeverTouched() {
    String shop = "shop-bulk";
    Employee bulk = it.employee(shop, "EMP-001", "Gita", null);
    Employee legacy = it.employee(shop, "EMP-002", "Hari", null);
    for (Employee e : List.of(bulk, legacy)) {
      AttendanceDay d = new AttendanceDay();
      d.setShopId(shop);
      d.setEmployeeId(e.getId());
      d.setDate(today());
      d.setStatus(AttendanceStatus.ABSENT);
      d.setSource(e == bulk ? AttendanceSource.BULK_PRESENT : null);
      it.template.insert(d);
    }
    HrSettings s = HrSettingsService.withDefaults(null, shop);
    Punch p = new Punch();
    p.setAt(Instant.now());
    PunchDayDeriver.Derived one = PunchDayService.derive(List.of(p), bulk, today(), s);

    assertNull(it.punchDays.writeDerived(shop, bulk, today(), s, one));
    assertNull(it.punchDays.writeDerived(shop, legacy, today(), s, one));
    assertEquals(AttendanceSource.BULK_PRESENT, day(shop, bulk).getSource());
    assertEquals(AttendanceStatus.ABSENT, day(shop, bulk).getStatus());
    assertNull(day(shop, legacy).getSource());
    assertEquals(AttendanceStatus.ABSENT, day(shop, legacy).getStatus());
  }

  @Test
  void theDedupeKeyIsUniquePerShop() {
    Punch a = new Punch();
    a.setShopId("shop-dedupe");
    a.setDedupeKey("S:e1:100");
    it.template.insert(a);
    Punch b = new Punch();
    b.setShopId("shop-dedupe");
    b.setDedupeKey("S:e1:100");
    assertThrows(DuplicateKeyException.class, () -> it.template.insert(b));
    Punch other = new Punch();
    other.setShopId("shop-dedupe-2");
    other.setDedupeKey("S:e1:100");
    it.template.insert(other);
  }

  @Test
  void refusalsStoreNothing() {
    String shop = "shop-refuse";
    settings(shop, OutsideAction.BLOCK);
    it.employee(shop, "EMP-001", "Esha", "user-esha");
    SelfCheckInRequest far = new SelfCheckInRequest();
    far.setLat(SHOP_LAT + 0.01);
    far.setLng(SHOP_LNG);
    far.setAccuracy(5.0);

    BaseException outside = assertThrows(BaseException.class,
        () -> it.selfCheckIn.checkIn(shop, "user-esha", far));
    assertEquals(ErrorCode.HR_OUTSIDE_SHOP, outside.getErrorCode());

    PayrollRun run = new PayrollRun();
    run.setShopId(shop);
    run.setPeriod(PayCycle.currentPeriod(1));
    run.setStatus(PayrollRunStatus.FINALISED);
    it.template.insert(run);
    BaseException locked = assertThrows(BaseException.class,
        () -> it.selfCheckIn.checkIn(shop, "user-esha", near(5)));
    assertEquals(ErrorCode.HR_PERIOD_LOCKED, locked.getErrorCode());

    assertEquals(0, punches(shop));
    assertEquals(0, it.template.count(Query.query(Criteria.where("shopId").is(shop)),
        AttendanceDay.class));
  }

  @Test
  void flaggedPunchesShowTheirDistanceToManagersAndOnTheRegister() {
    String shop = "shop-flag";
    settings(shop, OutsideAction.FLAG);
    Employee e = it.employee(shop, "EMP-001", "Farah", "user-farah");

    SelfCheckInResponse r = it.selfCheckIn.checkIn(shop, "user-farah", near(450));

    assertEquals(List.of(PunchFlag.LOW_ACCURACY), r.getFlags());
    List<PunchResponse> list = it.punchDays.listForEmployee(MANAGER, shop, e.getId(), today());
    assertEquals(1, list.size());
    assertEquals(r.getDistanceMetres(), list.get(0).getDistanceMetres());
    assertEquals(List.of(PunchFlag.LOW_ACCURACY), list.get(0).getFlags());
    assertTrue(list.get(0).isCounted());
    assertEquals(List.of(PunchFlag.LOW_ACCURACY),
        it.attendance.daily(MANAGER, shop, today()).getRows().get(0).getFlags());
    assertEquals(1, it.selfCheckIn.myPunches(shop, "user-farah", null).size());
  }
}
