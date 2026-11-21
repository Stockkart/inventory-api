package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.ManualMarkReason;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.request.UpdateHrSettingsRequest;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.rest.dto.response.HrSettingsResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.AttendanceDayValidator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The hand-mark reason rule end to end against a real MongoDB (R7.2–R7.8, R1.8–R1.9): the
 * Biometric app switch is turned on through the settings service exactly as {@code PUT
 * /api/v1/hr/settings} does, a phone is paired, and the attendance service is driven as the two
 * manual-marking endpoints drive it. Runs only with {@code -Dstockkart.test.mongo.uri=…}; every
 * test uses its own shop.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class AttendanceMarkReasonIT {

  private static final String USER = "owner-1";
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("markreason");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static String today() {
    return HrDateUtils.format(HrDateUtils.today());
  }

  private static String daysAgo(int n) {
    return HrDateUtils.format(HrDateUtils.today().minusDays(n));
  }

  /** The newest weekday on or before {@code from} that is not a Sunday (the default week-off). */
  private static String workingDayOnOrBefore(LocalDate from) {
    LocalDate d = from;
    while (d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
      d = d.minusDays(1);
    }
    return HrDateUtils.format(d);
  }

  private static SaveAttendanceDaysRequest.Row row(Employee e, String date, String status) {
    return new SaveAttendanceDaysRequest.Row(e.getId(), date, status, null, null, null);
  }

  private static SaveAttendanceDaysRequest.Row row(Employee e, String date, String status,
      ManualMarkReason reason, String reasonText) {
    return new SaveAttendanceDaysRequest.Row(e.getId(), date, status, null, null, null, reason,
        reasonText);
  }

  private static SaveAttendanceDaysRequest save(SaveAttendanceDaysRequest.Row... rows) {
    return new SaveAttendanceDaysRequest(new ArrayList<>(List.of(rows)));
  }

  private static long count(Class<?> type, String shopId) {
    return it.template.count(Query.query(Criteria.where("shopId").is(shopId)), type);
  }

  private static List<HrAuditEvent> audit(String shopId, HrAuditAction action) {
    return it.template.find(Query.query(Criteria.where("shopId").is(shopId).and("action")
        .is(action)), HrAuditEvent.class);
  }

  private static AttendanceDay day(String shopId, Employee e, String date) {
    return it.template.findOne(Query.query(Criteria.where("shopId").is(shopId)
        .and("employeeId").is(e.getId()).and("date").is(date)), AttendanceDay.class);
  }

  /** Flips the Biometric app switch the way {@code PUT /api/v1/hr/settings} does. */
  private static HrSettingsResponse setBiometricApp(String shopId, boolean on) {
    HrSettingsResponse current = it.settings.get(USER, shopId);
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    UpdateHrSettingsRequest.AttendanceCaptureChange capture =
        new UpdateHrSettingsRequest.AttendanceCaptureChange();
    capture.setBiometricApp(on);
    request.setAttendanceCapture(capture);
    request.setRevision(current.isSaved() ? current.getRevision() : null);
    return it.settings.update(USER, shopId, request);
  }

  /** An active pairing, as {@code DevicePairingService.complete} leaves it. */
  private static DevicePairing pair(String shopId, Employee e) {
    DevicePairing p = new DevicePairing();
    p.setShopId(shopId);
    p.setEmployeeId(e.getId());
    p.setStatus(PairingStatus.ACTIVE);
    p.setInstallationId(java.util.UUID.randomUUID().toString());
    p.setOs(DeviceOs.ANDROID);
    p.setPhoneModel("Redmi Note 11");
    p.setPairedAt(Instant.now());
    p.setPairedBy(USER);
    p.setCreatedAt(Instant.now());
    return it.template.insert(p);
  }

  /**
   * One app punch on {@code date} (09:00, or a minute ago when {@code date} is today so it is never
   * in the future) and the day it derives to (PRESENT, BIOMETRIC_APP).
   */
  private static AttendanceDay appPunchDerivedDay(String shopId, Employee e, String date,
      DevicePairing pairing) {
    Instant nine = date.equals(today()) ? Instant.now().minusSeconds(60)
        : HrDateUtils.parseDate(date).atStartOfDay(HrDateUtils.ZONE).toInstant()
            .plusSeconds(9 * 3600);
    Punch p = new Punch();
    p.setShopId(shopId);
    p.setEmployeeId(e.getId());
    p.setAt(nine);
    p.setLocalDate(date);
    p.setSource(PunchSource.BIOMETRIC_APP);
    p.setDirection(PunchDirection.IN);
    p.setFlags(new ArrayList<>());
    p.setDedupeKey("B:" + e.getId() + ":" + java.util.UUID.randomUUID());
    p.setPairingId(pairing.getId());
    p.setDeviceModel(pairing.getPhoneModel());
    p.setPhoneTime(nine);
    p.setCreatedAt(nine);
    it.template.insert(p);
    HrSettings settings = it.settings.effectiveSettings(shopId);
    AttendanceDay written = it.punchDays.writeDerived(shopId, e, date, settings,
        PunchDayService.derive(List.of(p), e, date, settings));
    assertNotNull(written, "the app punch derives a day");
    return day(shopId, e, date);
  }

  // ---- settings: the switch is audited and the response counts paired employees (R1.8–R1.9) --

  @Test
  void turningTheSwitchOnIsAuditedBeforeAfterAndCountsPairedEmployees() {
    String shop = "shop-reason-settings";
    Employee a = it.employee(shop, "EMP-001", "Asha", null);
    it.employee(shop, "EMP-002", "Bala", null);

    HrSettingsResponse off = it.settings.get(USER, shop);
    assertFalse(off.getAttendanceCapture().isBiometricApp(), "default off");
    assertEquals(0, off.getPairedEmployeeCount());

    HrSettingsResponse on = setBiometricApp(shop, true);
    assertTrue(on.getAttendanceCapture().isBiometricApp());
    assertEquals(0, on.getPairedEmployeeCount(), "nobody paired yet");

    // the first save has no stored row to snapshot, so the before is null; flip again for a
    // before/after pair that carries the switch
    HrSettingsResponse offAgain = setBiometricApp(shop, false);
    assertFalse(offAgain.getAttendanceCapture().isBiometricApp());
    List<HrAuditEvent> changes = audit(shop, HrAuditAction.SETTINGS_CHANGED);
    assertEquals(2, changes.size());
    HrAuditEvent first = changes.get(0);
    assertNull(first.getBefore(), "first save: nothing stored before");
    assertEquals(true, first.getAfter().get("biometricApp"));
    HrAuditEvent second = changes.get(1);
    assertEquals(true, second.getBefore().get("biometricApp"));
    assertEquals(false, second.getAfter().get("biometricApp"));

    // a paired current employee is counted; the switch position does not change the count
    pair(shop, a);
    assertEquals(1, it.settings.get(USER, shop).getPairedEmployeeCount());
    assertEquals(1, setBiometricApp(shop, true).getPairedEmployeeCount());
  }

  // ---- PUT /hr/attendance/days ----------------------------------------------------------------

  @Test
  void aPairedEmployeesHandMarkNeedsAReasonAndIsAuditedWithTheDerivedBefore() {
    String shop = "shop-reason-save";
    Employee paired = it.employee(shop, "EMP-001", "Asha", null);
    Employee unpaired = it.employee(shop, "EMP-002", "Bala", null);
    setBiometricApp(shop, true);
    DevicePairing pairing = pair(shop, paired);
    String date = workingDayOnOrBefore(HrDateUtils.today().minusDays(1));
    AttendanceDay derived = appPunchDerivedDay(shop, paired, date, pairing);
    assertEquals(AttendanceSource.BIOMETRIC_APP, derived.getSource());
    assertEquals("09:00", derived.getInTime());
    long daysBefore = count(AttendanceDay.class, shop);
    long auditBefore = count(HrAuditEvent.class, shop);

    // no reason: the whole call is refused with the R7.4 message, and nothing is written, not
    // even the unpaired employee's row in the same call
    ValidationException refused = assertThrows(ValidationException.class,
        () -> it.attendance.saveDays(USER, shop, save(row(paired, date, "ABSENT"),
            row(unpaired, date, "PRESENT"))));
    assertEquals(AttendanceDayValidator.REASON_REQUIRED, refused.getMessage());
    assertEquals("A reason is required when the employee uses the Attendance app",
        refused.getMessage());
    assertEquals(daysBefore, count(AttendanceDay.class, shop));
    assertEquals(auditBefore, count(HrAuditEvent.class, shop));
    AttendanceDay untouched = day(shop, paired, date);
    assertEquals(AttendanceStatus.PRESENT, untouched.getStatus());
    assertEquals(AttendanceSource.BIOMETRIC_APP, untouched.getSource());

    // OTHER without text is not a complete reason either
    assertThrows(ValidationException.class, () -> it.attendance.saveDays(USER, shop,
        save(row(paired, date, "ABSENT", ManualMarkReason.OTHER, "   "))));
    assertEquals(auditBefore, count(HrAuditEvent.class, shop));

    // FORGOT_PHONE: written, with its own ATTENDANCE_SET row carrying the reason and the
    // derived values as "before" (R7.5–R7.6); the unpaired employee's first mark is summarised
    List<AttendanceRowResponse> saved = it.attendance.saveDays(USER, shop,
        save(row(paired, date, "ABSENT", ManualMarkReason.FORGOT_PHONE, null),
            row(unpaired, date, "PRESENT")));
    assertEquals(2, saved.size());
    assertTrue(saved.get(0).isMarkReasonRequired());
    assertFalse(saved.get(1).isMarkReasonRequired());

    AttendanceDay marked = day(shop, paired, date);
    assertEquals(AttendanceStatus.ABSENT, marked.getStatus());
    assertEquals(AttendanceSource.MANUAL, marked.getSource());
    assertEquals(USER, marked.getMarkedByUserId());
    assertEquals(AttendanceStatus.PRESENT, day(shop, unpaired, date).getStatus());

    List<HrAuditEvent> sets = audit(shop, HrAuditAction.ATTENDANCE_SET);
    assertEquals(1, sets.size());
    HrAuditEvent set = sets.get(0);
    assertEquals(paired.getId(), set.getEmployeeId());
    assertEquals(date, set.getDate());
    assertEquals("FORGOT_PHONE", set.getReason());
    assertEquals("PRESENT", set.getBefore().get("status"));
    assertEquals("09:00", set.getBefore().get("inTime"));
    assertEquals("BIOMETRIC_APP", set.getBefore().get("source"));
    assertEquals("ABSENT", set.getAfter().get("status"));
    assertEquals("MANUAL", set.getAfter().get("source"));
    assertEquals(1, audit(shop, HrAuditAction.ATTENDANCE_BULK_SET).size(),
        "the unpaired employee's first mark shares the usual summary");

    // OTHER with text is recorded as "OTHER: <trimmed text>"
    it.attendance.saveDays(USER, shop,
        save(row(paired, date, "PRESENT", ManualMarkReason.OTHER, "  came by bus  ")));
    List<HrAuditEvent> after = audit(shop, HrAuditAction.ATTENDANCE_SET);
    assertEquals(2, after.size());
    assertTrue(after.stream().anyMatch(e -> "OTHER: came by bus".equals(e.getReason())));

    // the unpaired employee never needs one, with the switch on
    it.attendance.saveDays(USER, shop, save(row(unpaired, date, "HALF_DAY")));
    assertEquals(AttendanceStatus.HALF_DAY, day(shop, unpaired, date).getStatus());
  }

  @Test
  void withTheSwitchOffAPairedEmployeeNeedsNoReason() {
    String shop = "shop-reason-off";
    Employee paired = it.employee(shop, "EMP-001", "Asha", null);
    pair(shop, paired);
    String date = workingDayOnOrBefore(HrDateUtils.today().minusDays(1));

    List<AttendanceRowResponse> saved =
        it.attendance.saveDays(USER, shop, save(row(paired, date, "PRESENT")));
    assertFalse(saved.get(0).isMarkReasonRequired());
    assertEquals(AttendanceStatus.PRESENT, day(shop, paired, date).getStatus());
    assertTrue(audit(shop, HrAuditAction.ATTENDANCE_SET).isEmpty());

    // on → off keeps the pairing (R1.5) and drops the requirement again
    setBiometricApp(shop, true);
    assertTrue(it.attendance.daily(USER, shop, date).getRows().get(0).isMarkReasonRequired());
    setBiometricApp(shop, false);
    assertFalse(it.attendance.daily(USER, shop, date).getRows().get(0).isMarkReasonRequired());
    assertEquals(1, it.settings.get(USER, shop).getPairedEmployeeCount());
  }

  // ---- POST /hr/attendance/mark-unmarked-present ----------------------------------------------

  @Test
  void markUnmarkedPresentRefusesTheWholeBatchWithoutAReasonWhenAnyoneIsPaired() {
    String shop = "shop-reason-batch";
    Employee paired = it.employee(shop, "EMP-001", "Asha", null);
    Employee unpaired = it.employee(shop, "EMP-002", "Bala", null);
    setBiometricApp(shop, true);
    pair(shop, paired);
    String date = workingDayOnOrBefore(HrDateUtils.today().minusDays(1));
    List<String> dates = new ArrayList<>(List.of(date));

    ValidationException refused = assertThrows(ValidationException.class,
        () -> it.attendance.markUnmarkedPresent(USER, shop,
            new MarkUnmarkedPresentRequest(dates, null)));
    assertEquals(AttendanceDayValidator.REASON_REQUIRED, refused.getMessage());
    assertEquals(0, count(AttendanceDay.class, shop), "nothing written for anyone");
    assertEquals(0, count(HrAuditEvent.class, shop) - audit(shop,
        HrAuditAction.SETTINGS_CHANGED).size());

    // only the unpaired employee: no reason needed
    assertEquals(1, it.attendance.markUnmarkedPresent(USER, shop,
        new MarkUnmarkedPresentRequest(dates, List.of(unpaired.getId()))).getMarked());
    assertEquals(1, audit(shop, HrAuditAction.ATTENDANCE_BULK_SET).size());
    assertTrue(audit(shop, HrAuditAction.ATTENDANCE_SET).isEmpty());

    // with a reason: the paired employee's day is written and gets its own row with the reason
    int marked = it.attendance.markUnmarkedPresent(USER, shop,
        new MarkUnmarkedPresentRequest(dates, null, ManualMarkReason.NO_NETWORK, null))
        .getMarked();
    assertEquals(1, marked);
    AttendanceDay day = day(shop, paired, date);
    assertEquals(AttendanceStatus.PRESENT, day.getStatus());
    assertEquals(AttendanceSource.BULK_PRESENT, day.getSource());
    List<HrAuditEvent> sets = audit(shop, HrAuditAction.ATTENDANCE_SET);
    assertEquals(1, sets.size());
    assertEquals(paired.getId(), sets.get(0).getEmployeeId());
    assertEquals(date, sets.get(0).getDate());
    assertEquals("NO_NETWORK", sets.get(0).getReason());
    assertNull(sets.get(0).getBefore());
    assertEquals("BULK_PRESENT", sets.get(0).getAfter().get("source"));
    assertEquals(1, audit(shop, HrAuditAction.ATTENDANCE_BULK_SET).size(),
        "no second summary: the only inserted day was the paired employee's");
  }

  // ---- registers: markReasonRequired and noPunchYet (R7.7–R7.8) ------------------------------

  @Test
  void theRegistersFlagPairedEmployeesAndTodaysMissingPunch() {
    String shop = "shop-reason-register";
    Employee paired = it.employee(shop, "EMP-001", "Asha", null);
    Employee unpaired = it.employee(shop, "EMP-002", "Bala", null);
    Employee punched = it.employee(shop, "EMP-003", "Chitra", null);
    setBiometricApp(shop, true);
    pair(shop, paired);
    DevicePairing punchedPairing = pair(shop, punched);
    appPunchDerivedDay(shop, punched, today(), punchedPairing);

    DailyRegisterResponse daily = it.attendance.daily(USER, shop, today());
    AttendanceRowResponse asha = daily.getRows().stream()
        .filter(r -> r.getEmployeeId().equals(paired.getId())).findFirst().orElseThrow();
    AttendanceRowResponse bala = daily.getRows().stream()
        .filter(r -> r.getEmployeeId().equals(unpaired.getId())).findFirst().orElseThrow();
    AttendanceRowResponse chitra = daily.getRows().stream()
        .filter(r -> r.getEmployeeId().equals(punched.getId())).findFirst().orElseThrow();
    assertTrue(asha.isMarkReasonRequired());
    assertTrue(asha.isNoPunchYet(), "paired, switch on, today, no punch, no mark");
    assertFalse(bala.isMarkReasonRequired());
    assertFalse(bala.isNoPunchYet());
    assertTrue(chitra.isMarkReasonRequired());
    assertFalse(chitra.isNoPunchYet(), "she punched");
    assertEquals(PunchSource.BIOMETRIC_APP, chitra.getPunchSource());

    // yesterday is not today: no hint even without a punch
    String yesterday = daysAgo(1);
    DailyRegisterResponse past = it.attendance.daily(USER, shop, yesterday);
    assertFalse(past.getRows().stream().anyMatch(AttendanceRowResponse::isNoPunchYet));
    assertTrue(past.getRows().stream()
        .filter(r -> r.getEmployeeId().equals(paired.getId())).findFirst().orElseThrow()
        .isMarkReasonRequired());

    // a hand-made mark today clears the hint (and needs a reason)
    it.attendance.saveDays(USER, shop,
        save(row(paired, today(), "ABSENT", ManualMarkReason.PHONE_NOT_WORKING, null)));
    daily = it.attendance.daily(USER, shop, today());
    assertFalse(daily.getRows().stream()
        .filter(r -> r.getEmployeeId().equals(paired.getId())).findFirst().orElseThrow()
        .isNoPunchYet());

    MonthRegisterResponse month = it.attendance.month(USER, shop, today().substring(0, 7), null);
    for (MonthRegisterResponse.Row r : month.getRows()) {
      boolean expected = !r.getEmployeeId().equals(unpaired.getId());
      assertEquals(expected, r.isMarkReasonRequired(), r.getName());
    }
  }
}
