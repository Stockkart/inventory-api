package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.MongoExecutionTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;

@ExtendWith(MockitoExtension.class)
class HrQueryRunnerTest {

  @Mock private MetricsWrapper metrics;
  @InjectMocks private HrQueryRunner runner;

  @Test
  void aWrappedMaxTimeErrorBecomesHrQueryTooSlowAndIsCounted() {
    MongoExecutionTimeoutException driver =
        new MongoExecutionTimeoutException(50, "operation exceeded time limit");
    BaseException e = assertThrows(BaseException.class, () -> runner.read("attendance_month",
        () -> {
          throw new UncategorizedMongoDbException("wrapped", driver);
        }));

    assertEquals(ErrorCode.HR_QUERY_TOO_SLOW, e.getErrorCode());
    assertEquals(503, e.getErrorCode().getHttpStatus().value());
    verify(metrics).increment(HrMetricsConstants.QUERY_TIMEOUTS_TOTAL, 1, "module", "hr",
        "query", "attendance_month");
  }

  @Test
  void springsQueryTimeoutCountsToo() {
    assertThrows(BaseException.class, () -> runner.read("x", () -> {
      throw new QueryTimeoutException("slow");
    }));
  }

  @Test
  void otherErrorsPassThrough() {
    IllegalStateException boom = new IllegalStateException("boom");
    assertSame(boom, assertThrows(IllegalStateException.class, () -> runner.read("x", () -> {
      throw boom;
    })));
  }
}
