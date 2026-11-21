// Feature: HR-module, Property 15: A reason is required exactly when the switch is on and the employee is paired
// Feature: HR-module, Property 18: "No punch yet" predicate
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.ManualMarkReason;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.service.PunchDayService.DaySummary;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.AttendanceDayValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.BulkWriteUpsert;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.bson.BsonObjectId;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Properties 15 and 18 of the HR-module design (task 7.2), run against the real {@link
 * AttendanceService} over Mockito doubles wired the way {@code AttendanceServiceTest} does it
 * (the collections, the bulk, {@code PunchDayService.summaryByEmployeeDate} and {@code
 * DevicePairingService.activePairingsByEmployee}).
 *
 * <p><b>Property 15 — Validates: Requirements 7.2, 7.3, 7.4, 7.5.</b> For any switch state,
 * pairing state, reason (none, a quick pick, or OTHER with any text: null, whitespace-only, 0–250
 * characters with or without padding) and any hand-made change (a first mark, a changed mark, a
 * cleared mark, or "mark all unmarked Present"):
 *
 * <ul>
 *   <li>text longer than 200 characters after trimming is never saved (R7.3), whatever the switch
 *       or pairing;
 *   <li>otherwise the save is refused with exactly {@link AttendanceDayValidator#REASON_REQUIRED}
 *       iff the switch is on <em>and</em> the employee is paired <em>and</em> the reason is
 *       incomplete, where complete = a quick pick other than OTHER, or OTHER with 1–200 characters
 *       after trimming;
 *   <li>on a refusal nothing is written: no upsert, no remove, no bulk execute, no audit call;
 *   <li>when accepted, the HR history rows for the employee carrying a reason are: for a save,
 *       exactly one {@code ATTENDANCE_SET} iff a complete reason was given (recorded even when it
 *       was not required), labelled with the quick pick's name or {@code "OTHER: <trimmed>"}; for
 *       mark-unmarked, exactly one iff a complete reason was given and the employee is paired
 *       (unpaired employees keep the summary row).
 * </ul>
 *
 * The pure {@link AttendanceDayValidator#isCompleteReason} / {@link
 * AttendanceDayValidator#reasonLabel} pair is checked against the same model directly.
 *
 * <p><b>Property 18 — Validates: Requirements 7.7, 7.8.</b> For any register date (today or
 * not), switch, pairing, punch count and stored mark, the daily register's {@code noPunchYet} is
 * true iff the date is today, the switch is on, the employee is paired, no punch exists and no
 * mark is stored; and {@code markReasonRequired} is true iff the switch is on and the employee is
 * paired.
 */
class AttendanceServiceReasonProperties {

  /** A Tuesday in a past month: employed, not a week-off, not locked. */
  private static final String TUE = "2025-04-08";
  private static final String E1 = "e1";
  private static final String E2 = "e2";

  // ---- the model -----------------------------------------------------------------------------

  static boolean modelComplete(ManualMarkReason reason, String text) {
    if (reason == null) {
      return false;
    }
    if (reason != ManualMarkReason.OTHER) {
      return true;
    }
    int len = text == null ? 0 : text.trim().length();
    return len >= 1 && len <= AttendanceDayValidator.MAX_REASON_LENGTH;
  }

  static String modelLabel(ManualMarkReason reason, String text) {
    if (!modelComplete(reason, text)) {
      return null;
    }
    return reason == ManualMarkReason.OTHER ? "OTHER: " + text.trim() : reason.name();
  }

  static boolean tooLong(String text) {
    return text != null && text.trim().length() > AttendanceDayValidator.MAX_REASON_LENGTH;
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<ManualMarkReason> reasons() {
    return Arbitraries.frequencyOf(
        Tuple.of(2, Arbitraries.just((ManualMarkReason) null)),
        Tuple.of(2, Arbitraries.of(ManualMarkReason.FORGOT_PHONE,
            ManualMarkReason.PHONE_NOT_WORKING, ManualMarkReason.NO_NETWORK,
            ManualMarkReason.BIOMETRIC_NOT_WORKING)),
        Tuple.of(4, Arbitraries.just(ManualMarkReason.OTHER)));
  }

  /** null, whitespace only, or 0–250 visible characters padded with 0–3 spaces each side. */
  @Provide
  Arbitrary<String> reasonTexts() {
    Arbitrary<String> whitespace = Arbitraries.strings().withChars(' ', '\t', '\n')
        .ofMinLength(0).ofMaxLength(5);
    Arbitrary<String> padding = Arbitraries.strings().withChars(' ').ofMaxLength(3);
    // lengths around the 200 boundary are what matters: weight them
    Arbitrary<Integer> lengths = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.integers().between(0, 250)),
        Tuple.of(2, Arbitraries.integers().between(195, 205)),
        Tuple.of(1, Arbitraries.of(0, 1, 200, 201)));
    Arbitrary<String> visible = lengths.flatMap(n -> Arbitraries.strings()
        .withCharRange('a', 'z').withCharRange('A', 'Z').withChars("0123456789.,!-éñ中")
        .ofLength(n));
    Arbitrary<String> padded = Combinators.combine(padding, visible, padding)
        .as((l, v, r) -> l + v + r);
    return Arbitraries.frequencyOf(
        Tuple.of(2, Arbitraries.just((String) null)),
        Tuple.of(1, whitespace),
        Tuple.of(6, padded));
  }

  /** What the manager does by hand. */
  enum Change { FIRST_MARK, CHANGE_MARK, CLEAR_MARK, BULK_PRESENT }

  @Provide
  Arbitrary<Change> changes() {
    return Arbitraries.of(Change.values());
  }

  @Provide
  Arbitrary<AttendanceStatus> markStatuses() {
    return Arbitraries.of(AttendanceStatus.PRESENT, AttendanceStatus.HALF_DAY,
        AttendanceStatus.ABSENT, AttendanceStatus.WEEK_OFF, AttendanceStatus.HOLIDAY);
  }

  // ---- Property 15: the pure validator ---------------------------------------------------------

  @Property(tries = 300)
  void theValidatorAgreesWithTheModel(@ForAll("reasons") ManualMarkReason reason,
      @ForAll("reasonTexts") String text) {
    assertEquals(modelComplete(reason, text),
        AttendanceDayValidator.isCompleteReason(reason, text),
        "isCompleteReason(" + reason + ", " + show(text) + ")");
    assertEquals(modelLabel(reason, text), AttendanceDayValidator.reasonLabel(reason, text),
        "reasonLabel(" + reason + ", " + show(text) + ")");
    String label = AttendanceDayValidator.reasonLabel(reason, text);
    if (label != null && reason == ManualMarkReason.OTHER) {
      String carried = label.substring("OTHER: ".length());
      assertEquals(carried, carried.trim(), "the label carries the trimmed text");
      assertTrue(carried.length() >= 1
          && carried.length() <= AttendanceDayValidator.MAX_REASON_LENGTH);
    }
  }

  // ---- Property 15: the service ----------------------------------------------------------------

  @Property(tries = 300)
  void aReasonIsRequiredExactlyWhenTheSwitchIsOnAndTheEmployeeIsPaired(
      @ForAll boolean switchOn, @ForAll boolean paired, @ForAll("reasons") ManualMarkReason reason,
      @ForAll("reasonTexts") String text, @ForAll("changes") Change change,
      @ForAll("markStatuses") AttendanceStatus status) {
    Harness h = new Harness(switchOn, paired);
    boolean required = switchOn && paired;
    boolean complete = modelComplete(reason, text);
    String label = modelLabel(reason, text);
    String context = "switch=" + switchOn + " paired=" + paired + " reason=" + reason
        + " text=" + show(text) + " change=" + change + " status=" + status;

    Runnable call = switch (change) {
      case FIRST_MARK -> () -> h.service.saveDays(USER, SHOP, save(
          row(E1, TUE, status.name(), reason, text)));
      case CHANGE_MARK -> {
        // the stored mark differs from every status we save, so a write always happens
        h.stored(E1, TUE, status == AttendanceStatus.ABSENT
            ? AttendanceStatus.PRESENT : AttendanceStatus.ABSENT);
        yield () -> h.service.saveDays(USER, SHOP, save(
            row(E1, TUE, status.name(), reason, text)));
      }
      case CLEAR_MARK -> {
        h.stored(E1, TUE, status);
        yield () -> h.service.saveDays(USER, SHOP, save(
            row(E1, TUE, AttendanceDayValidator.UNMARKED, reason, text)));
      }
      case BULK_PRESENT -> () -> h.service.markUnmarkedPresent(USER, SHOP,
          new MarkUnmarkedPresentRequest(new ArrayList<>(List.of(TUE)), null, reason, text));
    };

    if (tooLong(text)) {
      // R7.3: over 200 characters is never saved, required or not
      ValidationException e = assertThrows(ValidationException.class, call::run, context);
      assertTrue(e.getMessage().contains("200"), context + " -> " + e.getMessage());
      h.assertNothingWritten(context, change != Change.BULK_PRESENT);
      return;
    }

    if (required && !complete) {
      ValidationException e = assertThrows(ValidationException.class, call::run, context);
      assertEquals(AttendanceDayValidator.REASON_REQUIRED, e.getMessage(), context);
      h.assertNothingWritten(context, change != Change.BULK_PRESENT);
      return;
    }

    // accepted
    call.run();
    verify(h.bulk, times(1).description(context)).execute();
    List<AuditEntry> entries = h.recordedAudit();
    List<AuditEntry> reasonRows = entries.stream()
        .filter(a -> a.action() == HrAuditAction.ATTENDANCE_SET && E1.equals(a.employeeId())
            && a.reason() != null)
        .toList();
    boolean expectReasonRow = change == Change.BULK_PRESENT ? complete && paired && switchOn
        : complete;
    assertEquals(expectReasonRow ? 1 : 0, reasonRows.size(), context + " -> " + entries);
    if (expectReasonRow) {
      AuditEntry entry = reasonRows.get(0);
      assertEquals(label, entry.reason(), context);
      assertEquals(TUE, entry.date(), context);
      assertEquals(USER, entry.actorUserId(), context);
      if (change == Change.CLEAR_MARK) {
        assertNull(entry.after(), context);
        assertEquals(status.name(), entry.before().get("status"), context);
      } else {
        assertEquals(change == Change.BULK_PRESENT ? AttendanceStatus.PRESENT.name()
            : status.name(), entry.after().get("status"), context);
        assertEquals(change == Change.BULK_PRESENT ? AttendanceSource.BULK_PRESENT.name()
            : AttendanceSource.MANUAL.name(), entry.after().get("source"), context);
      }
    }
    // no row of this call carries a reason that was not given
    for (AuditEntry a : entries) {
      if (a.reason() != null) {
        assertEquals(label, a.reason(), context);
      }
    }
    // a pairing is read only while the switch is on (R7.1)
    verify(h.pairingService, times(switchOn ? 1 : 0).description(context))
        .activePairingsByEmployee(eq(SHOP), anyCollection());
  }

  // ---- Property 18 -----------------------------------------------------------------------------

  @Provide
  Arbitrary<Integer> dayOffsets() {
    // 0 = today; past and future dates both count as "not today"
    return Arbitraries.frequencyOf(
        Tuple.of(1, Arbitraries.just(0)),
        Tuple.of(1, Arbitraries.integers().between(-40, 40)));
  }

  @Provide
  Arbitrary<Integer> punchCounts() {
    return Arbitraries.frequencyOf(
        Tuple.of(1, Arbitraries.just(0)),
        Tuple.of(1, Arbitraries.integers().between(0, 4)));
  }

  @Property(tries = 300)
  void noPunchYetIsTodayAndSwitchOnAndPairedAndNoPunchAndNoMark(
      @ForAll("dayOffsets") int dayOffset, @ForAll boolean switchOn, @ForAll boolean paired,
      @ForAll("punchCounts") int punchCount, @ForAll boolean summaryRowWhenZero,
      @ForAll boolean marked, @ForAll("markStatuses") AttendanceStatus markStatus) {
    LocalDate date = HrDateUtils.today().plusDays(dayOffset);
    String d = HrDateUtils.format(date);
    boolean today = dayOffset == 0;
    Harness h = new Harness(switchOn, paired);
    if (marked) {
      h.stored(E1, d, markStatus);
    }
    Map<String, DaySummary> summaries = new LinkedHashMap<>();
    if (punchCount > 0 || summaryRowWhenZero) {
      summaries.put(AttendanceService.key(E1, d), new DaySummary(
          punchCount > 0 ? EnumSet.of(PunchFlag.OUTSIDE_SHOP) : EnumSet.noneOf(PunchFlag.class),
          punchCount > 0 ? PunchSource.BIOMETRIC_APP : null, punchCount));
    }
    when(h.punchDays.summaryByEmployeeDate(eq(SHOP), anyCollection(), eq(d), anyLong()))
        .thenReturn(summaries);
    String context = "date=" + d + " today=" + today + " switch=" + switchOn + " paired="
        + paired + " punches=" + punchCount + " marked=" + marked;

    DailyRegisterResponse r = h.service.daily(USER, SHOP, d);

    AttendanceRowResponse row = r.getRows().stream()
        .filter(x -> E1.equals(x.getEmployeeId())).findFirst().orElseThrow();
    boolean expected = today && switchOn && paired && punchCount == 0 && !marked;
    assertEquals(expected, row.isNoPunchYet(), context);
    assertEquals(switchOn && paired, row.isMarkReasonRequired(), context);
    // the hint is not a status: the row still shows what is stored (or nothing)
    if (!marked) {
      assertNull(row.getMarkSource(), context);
    }
  }

  // ---- harness -------------------------------------------------------------------------------

  /** The service over doubles, in {@code AttendanceServiceTest}'s shape, for one try. */
  static final class Harness {
    final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    final BulkOperations bulk = mock(BulkOperations.class);
    final HrAuditService auditService = mock(HrAuditService.class);
    final PunchDayService punchDays = mock(PunchDayService.class);
    final DevicePairingService pairingService = mock(DevicePairingService.class);
    final AttendanceService service = new AttendanceService();
    private final List<AttendanceDay> days = new ArrayList<>();

    Harness(boolean switchOn, boolean pairedE1) {
      HrSettingsService settingsService = mock(HrSettingsService.class);
      HrSettings settings = HrSettingsService.withDefaults(null, SHOP);
      settings.getAttendanceCapture().setBiometricApp(switchOn);
      when(settingsService.effectiveSettings(SHOP)).thenReturn(settings);

      Map<String, DevicePairing> pairings = new LinkedHashMap<>();
      if (pairedE1) {
        DevicePairing p = new DevicePairing();
        p.setId("pair-" + E1);
        p.setEmployeeId(E1);
        p.setPhoneModel("Redmi Note 11");
        pairings.put(E1, p);
      }
      when(pairingService.activePairingsByEmployee(eq(SHOP), anyCollection()))
          .thenReturn(pairings);

      when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), eq(AttendanceDay.class)))
          .thenReturn(bulk);
      when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
          .thenReturn(List.of(employee(E1, "EMP-001"), employee(E2, "EMP-002")));
      when(mongoTemplate.find(any(Query.class), eq(LeaveRequest.class))).thenReturn(List.of());
      when(mongoTemplate.find(any(Query.class), eq(Holiday.class))).thenReturn(List.of());
      when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenAnswer(inv -> days);
      // mark-unmarked: both candidate days (e1/TUE index 0, e2/TUE index 1) are inserted
      BulkWriteResult result = mock(BulkWriteResult.class);
      when(result.getUpserts()).thenReturn(List.of(new BulkWriteUpsert(0, new BsonObjectId()),
          new BulkWriteUpsert(1, new BsonObjectId())));
      when(bulk.execute()).thenReturn(result);

      ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
      ReflectionTestUtils.setField(service, "guard", mock(HrPermissionGuard.class));
      ReflectionTestUtils.setField(service, "settingsService", settingsService);
      ReflectionTestUtils.setField(service, "periodLockService", mock(PeriodLockService.class));
      ReflectionTestUtils.setField(service, "auditService", auditService);
      ReflectionTestUtils.setField(service, "validator", new AttendanceDayValidator());
      ReflectionTestUtils.setField(service, "queryRunner", new HrQueryRunner());
      ReflectionTestUtils.setField(service, "metrics", mock(MetricsWrapper.class));
      ReflectionTestUtils.setField(service, "userNames", mock(HrUserNames.class));
      ReflectionTestUtils.setField(service, "punchDays", punchDays);
      ReflectionTestUtils.setField(service, "pairingService", pairingService);
    }

    void stored(String employeeId, String date, AttendanceStatus status) {
      AttendanceDay d = new AttendanceDay();
      d.setShopId(SHOP);
      d.setEmployeeId(employeeId);
      d.setDate(date);
      d.setStatus(status);
      d.setSource(AttendanceSource.MANUAL);
      days.add(d);
    }

    /**
     * Nothing reached the database or HR history. Mark-unmarked queues its upserts on the (not
     * yet executed) bulk before it knows whether a paired employee is among the candidates, so
     * for it only the execute matters; a save never queues before the reason check.
     */
    void assertNothingWritten(String context, boolean noQueuedOps) {
      if (noQueuedOps) {
        verify(bulk, never().description(context)).upsert(any(Query.class), any(Update.class));
        verify(bulk, never().description(context)).remove(any(Query.class));
      }
      verify(bulk, never().description(context)).execute();
      verify(auditService, never().description(context)).recordAll(anyList());
      verify(auditService, never().description(context)).record(any());
      verify(mongoTemplate, never().description(context)).save(any());
      verify(mongoTemplate, never().description(context)).insert(any());
      verify(mongoTemplate, never().description(context)).insertAll(anyCollection());
      verify(mongoTemplate, never().description(context)).remove(any(Query.class), anyString());
      verify(punchDays, never().description(context)).rederive(any(), any(), anyList(), any());
    }

    @SuppressWarnings("unchecked")
    List<AuditEntry> recordedAudit() {
      ArgumentCaptor<List<AuditEntry>> audit = ArgumentCaptor.forClass(List.class);
      verify(auditService).recordAll(audit.capture());
      return audit.getValue();
    }
  }

  private static Employee employee(String id, String code) {
    Employee e = new Employee();
    e.setId(id);
    e.setShopId(SHOP);
    e.setCode(code);
    e.setName("Name " + code);
    e.setJoinDate("2025-01-01");
    return e;
  }

  private static SaveAttendanceDaysRequest save(SaveAttendanceDaysRequest.Row... rows) {
    return new SaveAttendanceDaysRequest(new ArrayList<>(List.of(rows)));
  }

  private static SaveAttendanceDaysRequest.Row row(String employeeId, String date, String status,
      ManualMarkReason reason, String text) {
    return new SaveAttendanceDaysRequest.Row(employeeId, date, status, null, null, null, reason,
        text);
  }

  private static String show(String text) {
    return text == null ? "null" : "\"" + text.replace("\n", "\\n").replace("\t", "\\t") + "\""
        + "(" + text.length() + ")";
  }
}
