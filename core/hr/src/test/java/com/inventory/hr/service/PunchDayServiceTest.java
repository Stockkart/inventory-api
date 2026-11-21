package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.utils.HrDateUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class PunchDayServiceTest {

  private static final String DAY = "2025-04-08";
  private static final LocalDate D = LocalDate.of(2025, 4, 8);

  @Mock private MongoTemplate mongoTemplate;
  @Mock private PunchRepository punchRepository;
  @Mock private HrPermissionGuard guard;
  @Mock private HrSettingsService settingsService;
  @InjectMocks private PunchDayService service;

  private static Employee employee() {
    Employee e = new Employee();
    e.setId("e1");
    e.setShopId(SHOP);
    e.setJoinDate("2025-01-01");
    return e;
  }

  private static HrSettings settings() {
    return HrSettingsService.withDefaults(null, SHOP);
  }

  private static PunchDayDeriver.Derived derived(String... times) {
    List<Punch> punches = new java.util.ArrayList<>();
    for (int i = 0; i < times.length; i++) {
      punches.add(PunchDayDeriverTest.at("p" + i, D, times[i]));
    }
    return PunchDayService.derive(punches, employee(), DAY, settings());
  }

  @Test
  void theDerivedUpsertOnlyMatchesADerivedDayWithNoMorePunches() {
    AttendanceDay day = service.writeDerived(SHOP, employee(), DAY, settings(),
        derived("09:00", "18:00"));

    ArgumentCaptor<Query> filter = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).upsert(filter.capture(), update.capture(), eq(AttendanceDay.class));
    Document q = filter.getValue().getQueryObject();
    // a MANUAL, BULK_PRESENT or source-less row never matches, so it is never changed (R7.9);
    // a derived row of either source does
    @SuppressWarnings("unchecked")
    List<Object> sources = (List<Object>) ((Document) q.get("source")).get("$in");
    assertEquals(List.of(AttendanceSource.SELF_CHECK_IN, AttendanceSource.BIOMETRIC_APP),
        sources);
    @SuppressWarnings("unchecked")
    List<Document> or = (List<Document>) q.get("$or");
    assertEquals(2, ((Document) or.get(0).get("punchCount")).get("$lte"));
    Document set = (Document) update.getValue().getUpdateObject().get("$set");
    assertEquals(AttendanceStatus.PRESENT, set.get("status"));
    assertEquals("09:00", set.get("inTime"));
    assertEquals("18:00", set.get("outTime"));
    assertEquals(540, set.get("workedMinutes"));
    assertEquals(2, set.get("punchCount"));
    assertEquals(AttendanceSource.SELF_CHECK_IN, set.get("source"));
    assertEquals(AttendanceSource.SELF_CHECK_IN, day.getSource());
  }

  @Test
  void theDaySourceIsThatOfTheEarliestCountedPunch() {
    // R8.1–R8.2: app punch first → BIOMETRIC_APP; My HR punch first → SELF_CHECK_IN
    List<Punch> appFirst = List.of(PunchDayDeriverTest.app("a", D, "09:00"),
        PunchDayDeriverTest.at("b", D, "18:00"));
    List<Punch> selfFirst = List.of(PunchDayDeriverTest.at("a", D, "09:00"),
        PunchDayDeriverTest.app("b", D, "18:00"));

    AttendanceDay app = service.writeDerived(SHOP, employee(), DAY, settings(),
        PunchDayService.derive(appFirst, employee(), DAY, settings()));
    AttendanceDay self = service.writeDerived(SHOP, employee(), DAY, settings(),
        PunchDayService.derive(selfFirst, employee(), DAY, settings()));

    assertEquals(AttendanceSource.BIOMETRIC_APP, app.getSource());
    assertEquals(AttendanceSource.SELF_CHECK_IN, self.getSource());
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate, times(2)).upsert(any(Query.class), update.capture(),
        eq(AttendanceDay.class));
    assertEquals(AttendanceSource.BIOMETRIC_APP,
        ((Document) update.getAllValues().get(0).getUpdateObject().get("$set")).get("source"));
  }

  @Test
  void punchesWithoutASourceDeriveASelfCheckInDay() {
    assertEquals(AttendanceSource.SELF_CHECK_IN, PunchDayService.derivedSource(null));
    assertEquals(AttendanceSource.SELF_CHECK_IN, PunchDayService.derivedSource(PunchSource.SELF));
    assertEquals(AttendanceSource.BIOMETRIC_APP,
        PunchDayService.derivedSource(PunchSource.BIOMETRIC_APP));
  }

  @Test
  void aHandMadeMarkIsNotTouchedByALaterAppPunch() {
    // the filter (source in the two derived sources) did not match the MANUAL row, so the upsert
    // inserted and hit the unique day index: the mark stays as it was (R7.9)
    when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(AttendanceDay.class)))
        .thenThrow(new DuplicateKeyException("E11000"));
    List<Punch> late = List.of(PunchDayDeriverTest.app("a", D, "09:00",
        PunchFlag.RECORDED_OFFLINE));

    assertNull(service.writeDerived(SHOP, employee(), DAY, settings(),
        PunchDayService.derive(late, employee(), DAY, settings())));
    verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(AttendanceDay.class));
  }

  @Test
  void missingCheckOutIsWorkedOutForBothDerivedSourcesButNeverForAHandMadeMark() {
    AttendanceDay day = new AttendanceDay();
    day.setDate(HrDateUtils.format(HrDateUtils.today().minusDays(1)));
    day.setPunchCount(1);
    day.setSource(AttendanceSource.BIOMETRIC_APP);
    assertTrue(PunchDayService.missingCheckOut(day, employee(), settings()));
    day.setSource(AttendanceSource.SELF_CHECK_IN);
    assertTrue(PunchDayService.missingCheckOut(day, employee(), settings()));
    day.setSource(AttendanceSource.MANUAL);
    assertFalse(PunchDayService.missingCheckOut(day, employee(), settings()));
    day.setSource(null);
    assertFalse(PunchDayService.missingCheckOut(day, employee(), settings()));
  }

  @Test
  void countedFlagsReadEveryPunchOfTheDateAndKeepTheFlagsOfCountedOnesOnly() {
    Punch a = PunchDayDeriverTest.app("a", D, "09:00");
    Punch dup = PunchDayDeriverTest.app("b", D, "09:02", PunchFlag.OUTSIDE_SHOP);
    Punch out = PunchDayDeriverTest.app("c", D, "18:00", PunchFlag.RECORDED_OFFLINE);
    Punch other = PunchDayDeriverTest.at("d", D, "10:00");
    Punch otherDup = PunchDayDeriverTest.app("e", D, "10:01", PunchFlag.FAKE_LOCATION);
    for (Punch p : List.of(a, dup, out)) {
      p.setEmployeeId("e1");
    }
    other.setEmployeeId("e2");
    otherDup.setEmployeeId("e2");
    when(mongoTemplate.find(any(Query.class), eq(Punch.class)))
        .thenReturn(List.of(dup, out, a, otherDup, other));

    Map<String, Set<PunchFlag>> flags = service.countedFlagsByEmployeeDate(SHOP,
        List.of("e1", "e2", "e3"), DAY, 1000);

    // e1: OUTSIDE_SHOP is on the duplicate only → not a chip (R8.4, R8.7)
    assertEquals(Set.of(PunchFlag.RECORDED_OFFLINE), flags.get(AttendanceService.key("e1", DAY)));
    // e2: the only flag is on a duplicate → no entry at all
    assertNull(flags.get(AttendanceService.key("e2", DAY)));
    assertEquals(1, flags.size());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Punch.class));
    Document q = query.getValue().getQueryObject();
    // one date, $in employees, and no "flags present" filter: duplicates must be seen too
    assertEquals(DAY, q.get("localDate"));
    assertEquals(List.of("e1", "e2", "e3"), ((Document) q.get("employeeId")).get("$in"));
    assertFalse(q.containsKey("flags.0"));
  }

  @Test
  void countedFlagsWithNoEmployeesReadsNothing() {
    assertTrue(service.countedFlagsByEmployeeDate(SHOP, List.of(), DAY, 1000).isEmpty());
    verify(mongoTemplate, never()).find(any(Query.class), eq(Punch.class));
  }

  @Test
  void theDaySummaryCarriesCountedFlagsThePrimarySourceAndEveryPunch() {
    Punch self = PunchDayDeriverTest.at("a", D, "09:00");
    Punch dup = PunchDayDeriverTest.app("b", D, "09:02", PunchFlag.OUTSIDE_SHOP);
    Punch out = PunchDayDeriverTest.app("c", D, "18:00", PunchFlag.RECORDED_OFFLINE);
    for (Punch p : List.of(self, dup, out)) {
      p.setEmployeeId("e1");
    }
    when(mongoTemplate.find(any(Query.class), eq(Punch.class)))
        .thenReturn(List.of(out, dup, self));

    Map<String, PunchDayService.DaySummary> summaries =
        service.summaryByEmployeeDate(SHOP, List.of("e1", "e2"), DAY, 1000);

    PunchDayService.DaySummary e1 = summaries.get(AttendanceService.key("e1", DAY));
    assertEquals(Set.of(PunchFlag.RECORDED_OFFLINE), e1.countedFlags());
    // the earliest counted punch is the My HR one (R8.2); duplicates still count as punches
    assertEquals(PunchSource.SELF, e1.primarySource());
    assertEquals(3, e1.punchCount());
    assertNull(summaries.get(AttendanceService.key("e2", DAY)), "no punch, no entry");
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Punch.class));
    assertTrue(query.getValue().getFieldsObject().containsKey("source"));
  }

  @Test
  void punchRowsCarryTheDeviceModelOfAppPunchesOnly() {
    Punch app = PunchDayDeriverTest.app("a", D, "09:00");
    app.setDeviceModel("Redmi Note 11");
    Punch self = PunchDayDeriverTest.at("b", D, "18:00");

    List<com.inventory.hr.rest.dto.response.PunchResponse> rows =
        PunchDayService.toResponses(List.of(self, app), employee(), DAY, settings());

    assertEquals("Redmi Note 11", rows.get(0).getDeviceModel());
    assertEquals(PunchSource.BIOMETRIC_APP, rows.get(0).getSource());
    assertNull(rows.get(1).getDeviceModel(), "My HR punches have no phone model (R8.6)");
  }

  @Test
  void fewHoursMakeAnAutomaticHalfDayByTheExistingRule() {
    AttendanceDay day = service.writeDerived(SHOP, employee(), DAY, settings(),
        derived("09:00", "11:00"));

    assertEquals(AttendanceStatus.HALF_DAY, day.getStatus());
    assertEquals(Boolean.TRUE, day.getAutoHalfDay());
  }

  @Test
  void aHandMadeMarkOrANewerResultWinsAndNothingIsWritten() {
    // the filter did not match, so the upsert inserted and hit the unique day index
    when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(AttendanceDay.class)))
        .thenThrow(new DuplicateKeyException("E11000"));

    assertNull(service.writeDerived(SHOP, employee(), DAY, settings(), derived("09:00")));
  }

  @Test
  void noCountedPunchWritesNothing() {
    assertNull(service.writeDerived(SHOP, employee(), DAY, settings(), derived()));
    verify(mongoTemplate, never()).upsert(any(Query.class), any(Update.class),
        eq(AttendanceDay.class));
  }

  @Test
  void reDeriveReadsThePunchesOnceAndSkipsDaysWithout() {
    Punch p = PunchDayDeriverTest.at("p1", D, "09:30");
    p.setEmployeeId("e1");
    when(mongoTemplate.find(any(Query.class), eq(Punch.class))).thenReturn(List.of(p));

    Map<String, AttendanceDay> written = service.rederive(SHOP, Map.of("e1", employee()),
        List.of(new String[] {"e1", DAY}, new String[] {"e1", "2025-04-09"}), settings());

    assertEquals(1, written.size());
    assertEquals("09:30", written.get(AttendanceService.key("e1", DAY)).getInTime());
  }

  @Test
  void theManagerListNeedsViewAttendanceAndAnEmployeeOfTheShop() {
    when(mongoTemplate.findOne(any(Query.class), eq(Employee.class))).thenReturn(null);

    assertThrows(ResourceNotFoundException.class,
        () -> service.listForEmployee("u1", SHOP, "foreign", DAY));
    verify(guard).require("u1", SHOP, HrPermission.VIEW_ATTENDANCE);
  }

  @Test
  void theManagerListIs403WithoutViewAttendance() {
    when(guard.require("u1", SHOP, HrPermission.VIEW_ATTENDANCE)).thenThrow(
        new com.inventory.common.exception.BaseException(
            com.inventory.common.constants.ErrorCode.ACCESS_DENIED, "no"));

    assertThrows(com.inventory.common.exception.BaseException.class,
        () -> service.listForEmployee("u1", SHOP, "e1", DAY));
    verify(punchRepository, never()).findByShopIdAndEmployeeIdAndLocalDateOrderByAtAsc(
        any(), any(), any());
  }
}
