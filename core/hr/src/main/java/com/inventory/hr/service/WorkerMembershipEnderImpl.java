package com.inventory.hr.service;

import com.inventory.hr.domain.model.Employee;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The {@link WorkerMembershipEnder} that runs in the program (design DD14).
 *
 * <p>Order matters for retries: the account is released <em>first</em> and the ledger ({@code
 * Employee.membershipEndedAt}) is written <em>last</em>, so a failure between the two leaves the
 * ledger open and {@link WorkerExitJob} repeats the call on its next tick; {@link
 * WorkerAccounts#releaseShop} is conditional, so the repeat is harmless. The ledger write is one
 * conditional update on {@code membershipEndedAt == null}: two callers (the exit save and the job,
 * or two instances) cannot both count the same exit, and an exit already ended is a no-op.
 *
 * <p>A staff account (OWNER, ADMIN, MANAGER, CASHIER) keeps its staff membership (R13.4): only the
 * ledger is written for it, so the job stops scanning the row. {@code Employee.userId} is never
 * touched, so payslips stay reachable from My HR (R5.3).
 *
 * <p>The account is reached through the user module's {@code api} package ({@link WorkerAccounts},
 * the same downward call {@link WorkerJoinService} uses), since hr depends on user.
 */
@Slf4j
@Component
public class WorkerMembershipEnderImpl implements WorkerMembershipEnder {

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private WorkerAccounts workerAccounts;

  private Clock clock = Clock.systemUTC();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void onExit(Employee employee) {
    end(employee);
  }

  /**
   * As {@link #onExit}, answering whether this call closed the ledger.
   *
   * @return true when {@code membershipEndedAt} was set by this call; false when the employee has
   *     no linked login or the exit was already ended
   */
  public boolean end(Employee employee) {
    if (employee == null || employee.getId() == null || employee.getUserId() == null) {
      return false;
    }
    if (employee.getMembershipEndedAt() != null) {
      return false;
    }
    String userId = employee.getUserId();
    String shopId = employee.getShopId();
    if (workerAccounts.account(userId).map(AccountView::worker).orElse(false)) {
      workerAccounts.releaseShop(shopId, userId);
    }
    Query open = Query.query(Criteria.where("_id").is(employee.getId())
        .and("membershipEndedAt").is(null));
    Update close = new Update().set("membershipEndedAt", Instant.now(clock));
    UpdateResult result = mongoTemplate.updateFirst(open, close, Employee.class);
    boolean ended = result.getModifiedCount() > 0;
    if (ended) {
      log.info("[hr-worker] membership of employee {} (shop {}) ended for its exit",
          employee.getId(), shopId);
    }
    return ended;
  }
}
