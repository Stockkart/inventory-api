package com.inventory.hr.service;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.utils.HrDateUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends the WORKER memberships of exited employees once their exit date arrives (worker-app R5.2,
 * R5.3; design DD14), and sweeps join requests stuck in {@code APPROVING}.
 *
 * <p>The exit save ({@code EmployeeService}) ends the membership at once when the exit date is
 * today or earlier; a later exit date, or a save whose call failed, is this job's: once a minute it
 * reads every linked, {@code EXITED} employee whose {@code exitDate <= today} (Asia/Kolkata) and
 * whose ledger ({@code membershipEndedAt}) is still open, and hands each to {@link
 * WorkerMembershipEnder}. The ledger write is a conditional update, so the job is idempotent and
 * harmless when two instances run it; a row that fails is logged and read again on the next tick.
 *
 * <p>The read repeats the partial filter of the {@code exit_membership_pending} index exactly
 * ({@code userId: {$type: "string"}, membershipEndedAt: null}); {@code $exists} or {@code $ne:
 * null} on {@code userId} would not be recognised as a subset and would scan the collection.
 *
 * <p>{@code APPROVING} exists only inside the approval transaction (DD12); MongoDB aborts a
 * transaction whose connection dies, so a row cannot normally stay there. The same tick resets any
 * row left {@code APPROVING} for over {@link #APPROVING_GRACE} back to {@code PENDING} and logs it,
 * so a worker is never stuck waiting on a request nobody can decide.
 *
 * <p>Holds no state; the handbook's {@code IN_MEMORY_STATE.md} lists it under "Scheduled jobs".
 */
@Slf4j
@Component
public class WorkerExitJob {

  /** How long a request may sit in {@code APPROVING} before the sweep treats it as abandoned. */
  static final Duration APPROVING_GRACE = Duration.ofMinutes(5);

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private WorkerJoinRequestRepository joinRequestRepository;

  @Autowired private WorkerMembershipEnder membershipEnder;

  @Value("${hr.worker-exit.enabled:true}")
  private boolean enabled = true;

  private Clock clock = Clock.systemUTC();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${hr.worker-exit.interval-ms:60000}")
  public void run() {
    if (!enabled) {
      return;
    }
    Instant now = Instant.now(clock);
    try {
      int ended = endDue(now);
      if (ended > 0) {
        log.info("[hr-worker] exit job ended {} membership(s)", ended);
      }
    } catch (RuntimeException e) {
      // the read itself failed; the rows are untouched and the next tick tries again
      log.warn("[hr-worker] exit job failed: {}", e.getMessage(), e);
    }
    try {
      resetStuckApprovals(now);
    } catch (RuntimeException e) {
      log.warn("[hr-worker] APPROVING sweep failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Hands every due exit to the ender; one failure does not stop the others.
   *
   * @return how many employees were handed over without an error
   */
  public int endDue(Instant now) {
    LocalDate today = LocalDate.ofInstant(now, HrDateUtils.ZONE);
    List<Employee> due = mongoTemplate.find(dueQuery(today), Employee.class);
    int done = 0;
    for (Employee e : due) {
      try {
        membershipEnder.onExit(e);
        done++;
      } catch (RuntimeException ex) {
        log.warn("[hr-worker] ending the membership of employee {} (shop {}) failed, will retry: {}",
            e.getId(), e.getShopId(), ex.getMessage());
      }
    }
    return done;
  }

  /**
   * Linked, exited employees whose exit date has arrived and whose membership has not been ended.
   * The {@code userId} and {@code membershipEndedAt} predicates are written exactly as the {@code
   * exit_membership_pending} partial index states them (see {@code HrIndexMigration}).
   */
  static Query dueQuery(LocalDate today) {
    return Query.query(Criteria.where("status").is(EmployeeStatus.EXITED.name())
        .and("exitDate").lte(HrDateUtils.format(today))
        .and("userId").type(2)
        .and("membershipEndedAt").is(null));
  }

  /**
   * Resets requests left {@code APPROVING} for longer than {@link #APPROVING_GRACE} to {@code
   * PENDING}, one conditional update each, and logs every reset.
   *
   * @return how many rows were reset
   */
  public int resetStuckApprovals(Instant now) {
    Instant before = now.minus(APPROVING_GRACE);
    List<WorkerJoinRequest> stuck = joinRequestRepository
        .findByStatusAndUpdatedAtBefore(WorkerJoinStatus.APPROVING, before);
    int reset = 0;
    for (WorkerJoinRequest r : stuck) {
      try {
        Query stillApproving = Query.query(Criteria.where("_id").is(r.getId())
            .and("status").is(WorkerJoinStatus.APPROVING.name()));
        Update toPending = new Update()
            .set("status", WorkerJoinStatus.PENDING.name())
            .set("updatedAt", now);
        if (mongoTemplate.updateFirst(stillApproving, toPending, WorkerJoinRequest.class)
            .getModifiedCount() > 0) {
          reset++;
          log.warn("[hr-worker] join request {} (shop {}, user {}) was APPROVING since {}; "
              + "reset to PENDING", r.getId(), r.getShopId(), r.getUserId(), r.getUpdatedAt());
        }
      } catch (RuntimeException ex) {
        log.warn("[hr-worker] resetting join request {} failed, will retry: {}", r.getId(),
            ex.getMessage());
      }
    }
    return reset;
  }
}
