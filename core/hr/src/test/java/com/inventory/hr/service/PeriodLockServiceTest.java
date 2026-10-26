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
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import java.util.List;
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
  @InjectMocks private PeriodLockService service;

  private static PayrollRun run(String period) {
    PayrollRun r = new PayrollRun();
    r.setPeriod(period);
    r.setStatus(PayrollRunStatus.FINALISED);
    return r;
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
