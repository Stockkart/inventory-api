package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Locked-month check for HR writes (R5.14, R6.12): a month whose payroll run is FINALISING or
 * FINALISED rejects attendance, leave approve/record/cancel, holiday, weekly-off and
 * salary-revision changes with 409 {@code HR_PERIOD_LOCKED}. REOPENING counts as locked too: a
 * reopen that finds paid lines goes back to FINALISED, so a write that slipped in meanwhile would
 * land in a finalised month.
 *
 * <p>Ordering: callers check the lock before they write. A finalise that claims the run between
 * the check and the write is caught by the finalise recheck of {@code markedAt} / {@code
 * decidedAt} after its {@code claimedAt} (design "payroll finalise", task 8.5).
 */
@Service
public class PeriodLockService {

  static final List<PayrollRunStatus> LOCKED =
      List.of(PayrollRunStatus.FINALISING, PayrollRunStatus.FINALISED, PayrollRunStatus.REOPENING);

  @Autowired private MongoTemplate mongoTemplate;

  /**
   * @param shopId tenant shop
   * @param period {@code YYYY-MM}
   */
  public void assertOpen(String shopId, String period) {
    assertOpen(shopId, List.of(period));
  }

  /** One indexed read ({@code (shopId, period)} unique) for all periods of a request. */
  public void assertOpen(String shopId, Collection<String> periods) {
    Set<String> locked = lockedPeriods(shopId, periods);
    if (!locked.isEmpty()) {
      throw new BaseException(ErrorCode.HR_PERIOD_LOCKED,
          "Payroll for " + String.join(", ", locked)
              + " is finalised, so it cannot be changed. Reopen payroll first");
    }
  }

  /** The given periods that are locked (for read screens that show a "locked" flag). */
  public Set<String> lockedPeriods(String shopId, Collection<String> periods) {
    Set<String> locked = new TreeSet<>();
    if (periods == null || periods.isEmpty()) {
      return locked;
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId)
        .and("period").in(new TreeSet<>(periods))
        .and("status").in(LOCKED));
    query.fields().include("period");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    for (PayrollRun run : mongoTemplate.find(query, PayrollRun.class)) {
      locked.add(run.getPeriod());
    }
    return locked;
  }
}
