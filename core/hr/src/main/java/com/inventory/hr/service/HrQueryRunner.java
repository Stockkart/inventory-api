package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.MongoExecutionTimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

/**
 * Time limits for HR reads (design "Performance and capacity"): screen reads get {@link
 * #SCREEN_MAX_TIME_MS}, month-wide reads {@link #MONTH_MAX_TIME_MS}, set per query with {@code
 * Query.maxTimeMsec} or the aggregation's {@code maxTime}. {@link #read} turns a query that hit its
 * limit into 503 {@code HR_QUERY_TOO_SLOW} and counts it, whichever layer wrapped the driver's
 * {@link MongoExecutionTimeoutException}.
 */
@Slf4j
@Component
public class HrQueryRunner {

  /** Daily register, saves, leave screens. */
  public static final long SCREEN_MAX_TIME_MS = 3_000;
  /** Month register, mark-unmarked for a month, payroll inputs. */
  public static final long MONTH_MAX_TIME_MS = 10_000;
  /**
   * Driver batch size for the bulk reads (employees, a month of days, leave, payroll lines). The
   * default first batch is 101 documents, so a 300-employee month (9,300 day records) cost two
   * extra {@code getMore} round trips; with this every such read is one reply (slim projected
   * documents stay far below the 16 MB reply limit).
   */
  public static final int BULK_BATCH_SIZE = 50_000;

  @Autowired private MetricsWrapper metrics;

  /**
   * Runs the reads of one request step.
   *
   * @param queryName metric tag, e.g. {@code attendance_daily}
   */
  public <T> T read(String queryName, Supplier<T> reads) {
    try {
      return reads.get();
    } catch (RuntimeException e) {
      if (!isTimeout(e)) {
        throw e;
      }
      log.warn("[hr-query] {} hit its time limit", queryName);
      if (metrics != null) {
        metrics.increment(HrMetricsConstants.QUERY_TIMEOUTS_TOTAL, 1,
            "module", HrMetricsConstants.MODULE, "query", queryName);
      }
      throw new BaseException(ErrorCode.HR_QUERY_TOO_SLOW, ErrorCode.HR_QUERY_TOO_SLOW.getMessage(),
          e);
    }
  }

  /** True for the driver's maxTimeMS error however Spring translated or wrapped it. */
  static boolean isTimeout(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof MongoExecutionTimeoutException || t instanceof QueryTimeoutException) {
        return true;
      }
    }
    return false;
  }
}
