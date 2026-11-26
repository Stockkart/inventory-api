package com.inventory.hr.service;

import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.WorkerJoinEvent;
import com.inventory.hr.utils.constants.HrMetricsConstants.WorkerLoginResult;
import com.inventory.hr.utils.constants.HrMetricsConstants.WorkerLookupResult;
import com.inventory.metrics.MetricsWrapper;
import org.springframework.stereotype.Component;

/**
 * The one place the Stockkart Team counters are incremented (worker-app R14.2; design
 * "Metrics, audit, dashboard"). Every worker event maps to exactly one method here and every method
 * adds exactly {@code 1} to exactly one meter, so the tag value sets are closed by construction:
 *
 * <table>
 *   <tr><th>Event</th><th>Method</th><th>Meter</th><th>Tags</th></tr>
 *   <tr><td>WORKER account created</td><td>{@link #workerSignedUp()}</td>
 *       <td>{@code inventory_hr_worker_signups_total}</td><td>{@code module=hr}</td></tr>
 *   <tr><td>sign-in with {@code source = worker-app}</td><td>{@link #appLogin(boolean)}</td>
 *       <td>{@code inventory_hr_worker_app_logins_total}</td>
 *       <td>{@code module=hr}, {@code result} = {@code success} | {@code error}</td></tr>
 *   <tr><td>{@code GET /hr/worker/shops} answered</td><td>{@link #lookup(boolean)}</td>
 *       <td>{@code inventory_hr_worker_lookups_total}</td>
 *       <td>{@code module=hr}, {@code result} = {@code found} | {@code not_found}</td></tr>
 *   <tr><td>join request created</td><td>{@link #joinRequestCreated}</td>
 *       <td rowspan="4">{@code inventory_hr_worker_join_requests_total}</td>
 *       <td rowspan="4">{@code module=hr}, {@code shop}, {@code event} = {@code created} | {@code
 *       withdrawn} | {@code approved_created} | {@code approved_linked} | {@code rejected}</td></tr>
 *   <tr><td>withdrawn by the worker</td><td>{@link #joinRequestWithdrawn}</td></tr>
 *   <tr><td>approved (new employee or linked)</td><td>{@link #joinRequestApproved}</td></tr>
 *   <tr><td>rejected</td><td>{@link #joinRequestRejected}</td></tr>
 * </table>
 *
 * <p>Over any period {@code created = approved_created + approved_linked + rejected + withdrawn +
 * still pending}, per {@code shop} (Property 29); that only holds while every
 * call site goes through this class, which is why {@link WorkerJoinService} and {@code
 * WorkerAppAuthPortImpl} hold no {@link MetricsWrapper} of their own for these meters. A
 * rate-limited lookup, sign-up or join request is refused before anything is read and is not
 * counted here (Property 28); the refusal shows on {@code inventory_user_auth_total{outcome=error}}
 * for sign-ups and on the HTTP status counters for the rest.
 *
 * <p>{@code inventory_user_auth_total{source}} stays in {@code AuthService}: it is the user
 * module's meter and the user module cannot depend on HR.
 */
@Component
public class HrWorkerCounters {

  /** The {@code shop} tag of a request whose shop is unknown; never expected in practice. */
  static final String UNKNOWN_SHOP = "unknown";

  private final MetricsWrapper metrics;

  public HrWorkerCounters(MetricsWrapper metrics) {
    this.metrics = metrics;
  }

  /** One WORKER account was created (R1.5); the account has no shop yet, so no {@code shop} tag. */
  public void workerSignedUp() {
    metrics.increment(HrMetricsConstants.WORKER_SIGNUPS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE);
  }

  /** One sign-in with {@code source = worker-app} ended, successfully or not (R1.8). */
  public void appLogin(boolean success) {
    metrics.increment(HrMetricsConstants.WORKER_APP_LOGINS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE,
        HrMetricsConstants.TAG_RESULT,
        success ? WorkerLoginResult.SUCCESS : WorkerLoginResult.ERROR);
  }

  /** One shop lookup by owner e-mail was answered (R3.2, R3.3); every miss is {@code not_found}. */
  public void lookup(boolean found) {
    metrics.increment(HrMetricsConstants.WORKER_LOOKUPS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE,
        HrMetricsConstants.TAG_RESULT,
        found ? WorkerLookupResult.FOUND : WorkerLookupResult.NOT_FOUND);
  }

  /** A join request was inserted (R3.7). */
  public void joinRequestCreated(WorkerJoinRequest request) {
    joinRequest(request, WorkerJoinEvent.CREATED);
  }

  /** The worker took their own Pending request back (R3.6). */
  public void joinRequestWithdrawn(WorkerJoinRequest request) {
    joinRequest(request, WorkerJoinEvent.WITHDRAWN);
  }

  /**
   * An approver accepted the request (R4.5, R4.8): {@code approved_linked} when the outcome is
   * {@link JoinOutcome#APPROVED_LINKED}, {@code approved_created} otherwise. Called after the
   * approval transaction committed, so a rolled-back approval is never counted (R14.4).
   */
  public void joinRequestApproved(WorkerJoinRequest request, JoinOutcome outcome) {
    joinRequest(request, outcome == JoinOutcome.APPROVED_LINKED
        ? WorkerJoinEvent.APPROVED_LINKED : WorkerJoinEvent.APPROVED_CREATED);
  }

  /** An approver rejected the request (R4.7). */
  public void joinRequestRejected(WorkerJoinRequest request) {
    joinRequest(request, WorkerJoinEvent.REJECTED);
  }

  private void joinRequest(WorkerJoinRequest request, String event) {
    metrics.increment(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE,
        HrMetricsConstants.TAG_SHOP, request.getShopId() != null ? request.getShopId() : UNKNOWN_SHOP,
        HrMetricsConstants.TAG_EVENT, event);
  }
}
