package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.repository.HrSettingsRepository;
import com.inventory.hr.utils.PayCycle;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Locked months (design Property 12). */
@ExtendWith(MockitoExtension.class)
class PeriodLockServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private HrSettingsRepository settingsRepository;
  @InjectMocks private PeriodLockService service;

  private static PayrollRun run(String period) {
    PayrollRun r = new PayrollRun();
    r.setPeriod(period);
    r.setStatus(PayrollRunStatus.FINALISED);
    return r;
  }

  @Test
  void theStartDayComesFromTheSettingsAndDefaultsToOne() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    assertEquals(1, service.startDay(SHOP));
    HrSettings s = new HrSettings();
    s.setPayCycleStartDay(26);
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.of(s));
    assertEquals(26, service.startDay(SHOP));
  }

  @Test
  void aDateIn27SepIsLockedByAFinalised2026OctRunWithStartDay26() {
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("2026-10")));

    BaseException e = assertThrows(BaseException.class, () -> service.assertOpen(SHOP,
        PayCycle.periodsOf(List.of("2026-09-27"), 26)));

    assertEquals(ErrorCode.HR_PERIOD_LOCKED, e.getErrorCode());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(PayrollRun.class));
    assertEquals(List.of("2026-10"), List.copyOf((java.util.Collection<?>)
        ((Document) query.getValue().getQueryObject().get("period")).get("$in")));
  }

  @Test
  void anyLockedRunBlocksAStartDayChange() {
    when(mongoTemplate.exists(any(Query.class), eq(PayrollRun.class))).thenReturn(true);
    assertTrue(service.hasLockedRun(SHOP));
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).exists(query.capture(), eq(PayrollRun.class));
    assertEquals(SHOP, query.getValue().getQueryObject().get("shopId"));
  }

  @Test
  void aFinalisedPeriodIsRejectedWithTheLockCode() {
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("2025-04")));

    BaseException e = assertThrows(BaseException.class,
        () -> service.assertOpen(SHOP, List.of("2025-04", "2025-05")));

    assertEquals(ErrorCode.HR_PERIOD_LOCKED, e.getErrorCode());
    assertTrue(e.getMessage().contains("2025-04"));
  }

  @Test
  void oneQueryChecksEveryPeriodByShopAndLockedStatuses() {
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class))).thenReturn(List.of());

    assertDoesNotThrow(() -> service.assertOpen(SHOP, List.of("2025-04", "2025-05")));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(PayrollRun.class));
    Document q = query.getValue().getQueryObject();
    assertEquals(SHOP, q.get("shopId"));
    assertEquals(List.of("FINALISING", "FINALISED", "REOPENING"),
        ((List<?>) ((Document) q.get("status")).get("$in")).stream().map(String::valueOf)
            .toList());
    assertEquals(HrQueryRunner.SCREEN_MAX_TIME_MS, query.getValue().getMeta().getMaxTimeMsec());
  }

  @Test
  void noPeriodsNoQuery() {
    assertDoesNotThrow(() -> service.assertOpen(SHOP, List.of()));
    verifyNoInteractions(mongoTemplate);
  }
}
