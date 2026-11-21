package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.inventory.hr.domain.repository.PunchRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
  void theDerivedUpsertOnlyMatchesASelfCheckInDayWithNoMorePunches() {
    AttendanceDay day = service.writeDerived(SHOP, employee(), DAY, settings(),
        derived("09:00", "18:00"));

    ArgumentCaptor<Query> filter = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).upsert(filter.capture(), update.capture(), eq(AttendanceDay.class));
    Document q = filter.getValue().getQueryObject();
    // a MANUAL, BULK_PRESENT or source-less row never matches, so it is never changed
    assertEquals(AttendanceSource.SELF_CHECK_IN, q.get("source"));
    @SuppressWarnings("unchecked")
    List<Document> or = (List<Document>) q.get("$or");
    assertEquals(2, ((Document) or.get(0).get("punchCount")).get("$lte"));
    Document set = (Document) update.getValue().getUpdateObject().get("$set");
    assertEquals(AttendanceStatus.PRESENT, set.get("status"));
    assertEquals("09:00", set.get("inTime"));
    assertEquals("18:00", set.get("outTime"));
    assertEquals(540, set.get("workedMinutes"));
    assertEquals(2, set.get("punchCount"));
    assertEquals(AttendanceSource.SELF_CHECK_IN, day.getSource());
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
