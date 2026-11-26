// Feature: worker-app, Property 10: One audit row and one notification per decision, and the text keeps contact data out
// Feature: worker-app, Property 11: One shop per account, even under concurrent approvals
// Feature: worker-app, Property 30: Approval is all-or-nothing
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.response.JoinRequestDecision;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.service.UserShopMembershipService;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.statistics.Statistics;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Audit, concurrency and atomicity of join-request decisions (worker-app design, task 4.8), over
 * the real {@link WorkerJoinService} wired by {@link WorkerJoinMongoSupport} on a MongoDB replica
 * set: the one-shop lock ({@code findAndModify} on {@code users}), the {@code PENDING → APPROVING}
 * lock on the request and the {@code MongoTx} transaction are database behaviour and cannot be
 * shown with mocks.
 *
 * <p><b>Property 10 — Validates: Requirements 4.11, 4.13, 10.2, 10.3.</b> For any batch of
 * requests and decisions on them (approve-created, approve-linked, reject with or
 * without a reason, withdraw): the filing of each request writes exactly one {@code
 * JOIN_REQUEST_RECEIVED} notification to the shop's approvers and no audit row; each approval or
 * rejection writes exactly one {@code hr_audit_events} row on the request (action, approver,
 * request id, outcome, employee id for approvals, reason for rejections) and, for approvals, one
 * {@code EMPLOYEE_LINK_CHANGED} row on the employee; each approval or rejection writes exactly one
 * notification to the requesting account ({@code JOIN_REQUEST_APPROVED} whichever approval mode
 * was used, R10.2; {@code JOIN_REQUEST_REJECTED}); a withdrawal
 * (R3.6) writes neither; and no notification text, and no request audit row, contains the
 * snapshot's phone or e-mail, while every decision text names the shop and a rejection text
 * carries the reason word for word.
 *
 * <p><b>Property 11 — Validates: Requirements 5.1.</b> For any WORKER account with one pending
 * request and {@code k ≥ 2} approvers approving it at the same time with any mix of {@code CREATE}
 * and {@code LINK}: exactly one approval succeeds and the others answer {@code
 * HR_REQUEST_ALREADY_HANDLED}, leaving one linked employee, one WORKER membership and {@code
 * UserAccount.shopId} naming the shop. When the account already holds another shop ({@code shopId}
 * set, or an ACTIVE employee linked to it there, either written straight into the database as a
 * stale document would be), every concurrent approval in this shop answers {@code
 * HR_WORKER_LOGIN_IN_OTHER_SHOP}, the request stays {@code PENDING}, and nothing is created.
 * After every interleaving the account has at most one active WORKER membership and at most one
 * linked ACTIVE employee.
 *
 * <p><b>Property 30 — Validates: Requirements 14.4.</b> For any pending request and a failure
 * injected after any step of the approval (lock,
 * shop claim, employee insert or link, membership insert, request update) through the service's
 * {@code afterStep} seam: the call fails with that failure, every collection is byte-identical to
 * before (request {@code PENDING} and not {@code APPROVING}, no employee, no membership, {@code
 * shopId} unchanged, no audit row, no notification, the {@code EMP-nnn} counter and the metrics
 * unmoved), and the same approval without the fault then succeeds.
 */
@AddLifecycleHook(value = WorkerJoinMongoSupport.RequiresReplicaSet.class,
    propagateTo = PropagationMode.ALL_DESCENDANTS)
class WorkerJoinServiceAtomicityProperties {

  static final String SHOP_1 = "shop-1";
  static final String SHOP_2 = "shop-2";
  static final String SHOP_3 = "shop-3";
  static final List<String> SHOPS = List.of(SHOP_1, SHOP_2, SHOP_3);
  static final String APPROVER = "manager-1";
  static final String REQUEST_ENTITY = WorkerJoinService.ENTITY;

  static final List<String> NAMES = List.of("Priya Sharma", "Ravi Kumar", "Kavya Nair",
      "Arjun Mehta", "Sita Devi", "Mohan Lal");

  private static WorkerJoinMongoSupport db;

  private static synchronized WorkerJoinMongoSupport db() {
    if (db == null) {
      db = new WorkerJoinMongoSupport("atomicity");
    }
    return db;
  }

  @AfterContainer
  static void closeDatabase() {
    if (db != null) {
      db.close();
      db = null;
    }
  }

  // =============================================================================================
  // generators
  // =============================================================================================

  /** A 10-digit Indian mobile: already normalised, so the snapshot holds exactly this string. */
  static final Arbitrary<String> PHONE = Combinators.combine(
      Arbitraries.of('6', '7', '8', '9'),
      Arbitraries.strings().withCharRange('0', '9').ofLength(9)).as((first, rest) -> first + rest);

  /** The local part of an e-mail; the address is {@code w<i>.<local>@example.com}, lower-case. */
  static final Arbitrary<String> EMAIL_LOCAL =
      Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10);

  static final Arbitrary<String> NAME = Arbitraries.of(NAMES);
  static final Arbitrary<String> SHOP = Arbitraries.of(SHOPS);

  /**
   * A rejection reason: null, blank, or 1–200 letters and punctuation, sometimes padded with
   * spaces (R4.7). No digits and no {@code @}, so a reason can never look like a phone or e-mail.
   */
  static final Arbitrary<String> REASON = Arbitraries.oneOf(
      Arbitraries.just(""),
      Arbitraries.just("   "),
      Arbitraries.strings().withCharRange('a', 'z').withChars(" .,!'").ofMinLength(1)
          .ofMaxLength(200).filter(s -> !s.isBlank()),
      Arbitraries.strings().withCharRange('a', 'z').withChars(' ').ofMinLength(1).ofMaxLength(190)
          .filter(s -> !s.isBlank()).map(s -> "  " + s + " ")).injectNull(0.2);

  enum Decision { APPROVE_CREATE, APPROVE_LINK, REJECT, WITHDRAW }

  /** One request and what happens to it (Property 10). */
  record Case(String name, String phone, String emailLocal, String shop, Decision decision,
      String reason) {}

  @Provide
  Arbitrary<List<Case>> batches() {
    Arbitrary<Case> one = Combinators.combine(NAME, PHONE, EMAIL_LOCAL, SHOP,
        Arbitraries.of(Decision.class), REASON).as(Case::new);
    return one.list().ofMinSize(1).ofMaxSize(4);
  }

  /** How an account already holds another shop before a concurrent approval (Property 11). */
  enum Elsewhere { NONE, SHOP_ID_SET, ACTIVE_EMPLOYEE_LINKED }

  record Race(String name, String phone, String emailLocal, String shop,
      List<JoinApproveMode> modes, Elsewhere elsewhere) {}

  @Provide
  Arbitrary<Race> races() {
    Arbitrary<List<JoinApproveMode>> modes =
        Arbitraries.of(JoinApproveMode.class).list().ofMinSize(2).ofMaxSize(6);
    Arbitrary<Elsewhere> elsewhere = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.just(Elsewhere.NONE)),
        Tuple.of(1, Arbitraries.just(Elsewhere.SHOP_ID_SET)),
        Tuple.of(1, Arbitraries.just(Elsewhere.ACTIVE_EMPLOYEE_LINKED)));
    return Combinators.combine(NAME, PHONE, EMAIL_LOCAL, SHOP, modes, elsewhere).as(Race::new);
  }

  /**
   * A pending request and the step after which the approval fails (Property 30). Only a WORKER
   * account can file a request (R5.6), so there is no staff case.
   */
  record Fault(String name, String phone, String emailLocal, String shop, JoinApproveMode mode,
      String failAfter) {}

  @Provide
  Arbitrary<Fault> faults() {
    return Combinators.combine(NAME, PHONE, EMAIL_LOCAL, SHOP,
            Arbitraries.of(JoinApproveMode.class), Arbitraries.of(WorkerJoinService.APPROVE_STEPS))
        .as(Fault::new);
  }

  // =============================================================================================
  // Property 10
  // =============================================================================================

  @Property(tries = 30)
  void property10OneAuditRowAndOneNotificationPerDecisionWithoutContactData(
      @ForAll("batches") List<Case> batch) {
    World w = new World(db());
    List<Filed> filed = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      Case c = batch.get(i);
      String userId = "w" + i;
      String email = userId + "." + c.emailLocal() + "@example.com";
      w.worker(userId, c.name(), c.phone(), email, null);
      String linkTarget = w.employee(c.shop(), "L-" + i, "Link Target " + i, null, null,
          EmployeeStatus.ACTIVE, null);
      String requestId = db.service.create(userId, c.shop()).getId();
      filed.add(new Filed(c, userId, email, requestId, linkTarget));
      Statistics.label("case").collect(c.decision().name());
    }
    // filing: one APPROVERS row each, no audit row, no contact data (R4.13, R10.3)
    for (Filed f : filed) {
      List<HrNotification> rows = w.notificationsAbout(f.requestId);
      assertEquals(1, rows.size(), "one notification on filing " + f.requestId);
      HrNotification n = rows.get(0);
      assertEquals(HrNotificationType.JOIN_REQUEST_RECEIVED, n.getType());
      assertEquals(NotificationAudience.APPROVERS, n.getAudience());
      assertEquals(f.c.shop(), n.getShopId());
      assertNull(n.getRecipientUserId(), "an approver row names no recipient");
      assertTrue(n.getText().contains(f.c.name()), "the approvers see the worker's name");
      assertNoContactData(n.getText(), f);
      assertEquals(0, w.requestAuditRows(f.requestId).size(), "filing writes no audit row");
    }
    // decisions
    for (Filed f : filed) {
      decide(w, f);
    }
    // totals: every row is accounted for by exactly one event
    long decisions = filed.stream()
        .filter(f -> f.c.decision() != Decision.WITHDRAW).count();
    long approvals = filed.stream()
        .filter(f -> f.c.decision() == Decision.APPROVE_CREATE
            || f.c.decision() == Decision.APPROVE_LINK).count();
    assertEquals(decisions, w.allAudit().stream()
        .filter(a -> REQUEST_ENTITY.equals(a.getEntityType())).count(),
        "request audit rows = approvals + rejections");
    assertEquals(approvals, w.allAudit().stream()
        .filter(a -> a.getAction() == HrAuditAction.EMPLOYEE_LINK_CHANGED).count(),
        "one link-changed row per approval");
    assertEquals(filed.size() + decisions, w.allNotifications().size(),
        "notifications = filings + approvals + rejections");
  }

  record Filed(Case c, String userId, String email, String requestId, String linkTarget) {}

  private void decide(World w, Filed f) {
    Case c = f.c;
    switch (c.decision()) {
      case APPROVE_CREATE, APPROVE_LINK -> {
        boolean link = c.decision() == Decision.APPROVE_LINK;
        JoinRequestDecision d = db.service.approve(APPROVER, c.shop(), f.requestId,
            link ? JoinApproveMode.LINK : JoinApproveMode.CREATE, link ? f.linkTarget : null);
        JoinOutcome expected = link ? JoinOutcome.APPROVED_LINKED : JoinOutcome.APPROVED_CREATED;
        assertEquals(expected, d.getOutcome());
        assertNotNull(d.getEmployeeId());

        List<HrAuditEvent> audit = w.requestAuditRows(f.requestId);
        assertEquals(1, audit.size(), "exactly one audit row for the approval");
        HrAuditEvent a = audit.get(0);
        assertEquals(HrAuditAction.JOIN_REQUEST_APPROVED, a.getAction());
        assertEquals(APPROVER, a.getActorUserId(), "who");
        assertEquals(c.shop(), a.getShopId());
        assertEquals(f.requestId, a.getEntityId(), "the request id");
        assertEquals(d.getEmployeeId(), a.getEmployeeId(), "the employee id");
        assertNotNull(a.getAt(), "when");
        assertEquals(expected.name(), a.getAfter().get("outcome"), "the outcome");
        assertNull(a.getReason());
        assertNoContactData(w.rawAudit(a.getId()).toJson(), f);
        List<HrAuditEvent> onEmployee = w.allAudit().stream()
            .filter(e -> e.getAction() == HrAuditAction.EMPLOYEE_LINK_CHANGED
                && d.getEmployeeId().equals(e.getEmployeeId()))
            .toList();
        assertEquals(1, onEmployee.size(), "one history entry on the employee (R4.11)");
        assertEquals(APPROVER, onEmployee.get(0).getActorUserId());

        HrNotification n = decisionNotification(w, f);
        assertEquals(HrNotificationType.JOIN_REQUEST_APPROVED, n.getType(),
            "an approval that links sends JOIN_REQUEST_APPROVED like any other (R10.2)");
        assertTrue(n.getText().contains(w.shopName(c.shop())), "names the shop: " + n.getText());
      }
      case REJECT -> {
        JoinRequestDecision d = db.service.reject(APPROVER, c.shop(), f.requestId, c.reason());
        assertEquals(JoinOutcome.REJECTED, d.getOutcome());
        assertNull(d.getEmployeeId());
        String clean = c.reason() == null || c.reason().trim().isEmpty() ? null : c.reason().trim();

        List<HrAuditEvent> audit = w.requestAuditRows(f.requestId);
        assertEquals(1, audit.size(), "exactly one audit row for the rejection");
        HrAuditEvent a = audit.get(0);
        assertEquals(HrAuditAction.JOIN_REQUEST_REJECTED, a.getAction());
        assertEquals(APPROVER, a.getActorUserId(), "who");
        assertEquals(c.shop(), a.getShopId());
        assertEquals(f.requestId, a.getEntityId(), "the request id");
        assertNull(a.getEmployeeId(), "a rejection names no employee");
        assertNotNull(a.getAt(), "when");
        assertEquals(JoinOutcome.REJECTED.name(), a.getAfter().get("outcome"), "the outcome");
        assertEquals(clean, a.getReason(), "the reason when given");
        assertNoContactData(w.rawAudit(a.getId()).toJson(), f);

        HrNotification n = decisionNotification(w, f);
        assertEquals(HrNotificationType.JOIN_REQUEST_REJECTED, n.getType());
        assertTrue(n.getText().contains(w.shopName(c.shop())), "names the shop: " + n.getText());
        if (clean != null) {
          assertTrue(n.getText().contains(clean), "the reason verbatim: " + n.getText());
        }
        Statistics.label("rejection reason").collect(clean == null ? "none" : "given");
      }
      case WITHDRAW -> {
        db.service.withdraw(f.userId, f.requestId);
        assertEquals(0, w.requestAuditRows(f.requestId).size(),
            "a withdrawal is not an approver's decision: no audit row (R4.11)");
        assertEquals(1, w.notificationsAbout(f.requestId).size(),
            "a withdrawal notifies nobody (R4.13): only the filing row remains");
      }
      default -> throw new IllegalStateException(c.decision().name());
    }
  }

  /** The one USER row about the request, addressed to the requester, carrying no contact data. */
  private HrNotification decisionNotification(World w, Filed f) {
    List<HrNotification> toWorker = w.notificationsAbout(f.requestId).stream()
        .filter(n -> n.getAudience() == NotificationAudience.USER).toList();
    assertEquals(1, toWorker.size(), "exactly one notification to the worker");
    HrNotification n = toWorker.get(0);
    assertEquals(f.userId, n.getRecipientUserId(), "addressed to the requesting account");
    assertEquals(f.requestId, n.getJoinRequestId());
    assertNoContactData(n.getText(), f);
    return n;
  }

  private static void assertNoContactData(String text, Filed f) {
    assertNotNull(text);
    assertFalse(text.contains(f.c.phone()), "phone leaked: " + text);
    assertFalse(text.toLowerCase().contains(f.email.toLowerCase()), "e-mail leaked: " + text);
    assertFalse(text.toLowerCase().contains(f.email.substring(0, f.email.indexOf('@'))
        .toLowerCase()), "e-mail local part leaked: " + text);
  }

  // =============================================================================================
  // Property 11
  // =============================================================================================

  @Property(tries = 25)
  void property11OneShopPerAccountEvenUnderConcurrentApprovals(@ForAll("races") Race race)
      throws Exception {
    World w = new World(db());
    String userId = "racer";
    String email = userId + "." + race.emailLocal() + "@example.com";
    w.worker(userId, race.name(), race.phone(), email, null);
    String shop = race.shop();
    String linkTarget = w.employee(shop, "L-1", "Link Target", null, null,
        EmployeeStatus.ACTIVE, null);
    String requestId = db.service.create(userId, shop).getId();
    // the stale situation is written straight into the database, after the request was filed
    String otherShop = SHOPS.stream().filter(s -> !s.equals(shop)).findFirst().orElseThrow();
    String staleShopId = null;
    switch (race.elsewhere()) {
      case SHOP_ID_SET -> {
        db.template.getCollection("users").updateOne(new Document("_id", userId),
            new Document("$set", new Document("shopId", otherShop)));
        staleShopId = otherShop;
      }
      case ACTIVE_EMPLOYEE_LINKED -> w.employee(otherShop, "X-1", "Stale Link", null, null,
          EmployeeStatus.ACTIVE, userId);
      case NONE -> { }
      default -> throw new IllegalStateException();
    }
    int k = race.modes().size();
    Statistics.label("race").collect("k=" + k + " " + race.elsewhere());
    double approvedBefore = w.approvedCount();

    List<Callable<Object>> calls = new ArrayList<>();
    CyclicBarrier go = new CyclicBarrier(k);
    for (int i = 0; i < k; i++) {
      JoinApproveMode mode = race.modes().get(i);
      String approver = "manager-" + (i + 1);
      calls.add(() -> {
        go.await(30, TimeUnit.SECONDS);
        try {
          return db.service.approve(approver, shop, requestId, mode,
              mode == JoinApproveMode.LINK ? linkTarget : null);
        } catch (BaseException e) {
          return e;
        }
      });
    }
    List<Object> answers = runTogether(calls);

    List<JoinRequestDecision> wins = new ArrayList<>();
    Map<ErrorCode, Integer> refusals = new TreeMap<>();
    for (Object a : answers) {
      if (a instanceof JoinRequestDecision d) {
        wins.add(d);
      } else if (a instanceof BaseException e) {
        refusals.merge(e.getErrorCode(), 1, Integer::sum);
      } else {
        fail("unexpected answer " + a);
      }
    }

    WorkerJoinRequest request = db.template.findById(requestId, WorkerJoinRequest.class);
    UserAccount account = db.template.findById(userId, UserAccount.class);
    List<Employee> linked = db.template.findAll(Employee.class).stream()
        .filter(e -> userId.equals(e.getUserId()) && e.getStatus() == EmployeeStatus.ACTIVE)
        .toList();
    List<UserShopMembership> memberships = db.template.findAll(UserShopMembership.class).stream()
        .filter(m -> userId.equals(m.getUserId()) && m.getRole() == UserRole.WORKER
            && m.isActive())
        .toList();
    assertFalse(request.getStatus() == WorkerJoinStatus.APPROVING, "never left locked");

    if (race.elsewhere() == Elsewhere.NONE) {
      assertEquals(1, wins.size(), "exactly one approval succeeds; answers " + answers);
      assertEquals(Map.of(ErrorCode.HR_REQUEST_ALREADY_HANDLED, k - 1), refusals,
          "the others are told the request was already handled");
      JoinRequestDecision win = wins.get(0);
      assertEquals(WorkerJoinStatus.APPROVED, request.getStatus());
      assertEquals(win.getOutcome(), request.getOutcome());
      assertEquals(win.getEmployeeId(), request.getEmployeeId());
      assertTrue(request.getDecidedByUserId().startsWith("manager-"), "decided by the winner");
      assertEquals(shop, account.getShopId(), "UserAccount.shopId names the shop");
      assertEquals(1, linked.size(), "one linked ACTIVE employee");
      assertEquals(win.getEmployeeId(), linked.get(0).getId());
      assertEquals(shop, linked.get(0).getShopId());
      assertEquals(1, memberships.size(), "one WORKER membership");
      assertEquals(shop, memberships.get(0).getShopId());
      assertEquals(1, w.requestAuditRows(requestId).size(), "one audit row for the one approval");
      assertEquals(1, w.notificationsAbout(requestId).stream()
          .filter(n -> n.getAudience() == NotificationAudience.USER).count(),
          "one notification for the one approval");
      assertEquals(1.0, w.approvedCount() - approvedBefore, 1e-9, "counted once");
    } else {
      assertEquals(0, wins.size(), "no approval succeeds in another shop; answers " + answers);
      assertEquals(Map.of(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP, k), refusals,
          "every approver is told the login is in another shop");
      assertEquals(WorkerJoinStatus.PENDING, request.getStatus(), "the request stays PENDING");
      assertNull(request.getDecidedByUserId());
      assertEquals(staleShopId, account.getShopId(), "UserAccount.shopId unchanged");
      assertTrue(linked.stream().noneMatch(e -> shop.equals(e.getShopId())),
          "no employee was created or linked in this shop");
      assertNull(db.template.findById(linkTarget, Employee.class).getUserId(),
          "the link target is still unlinked");
      assertEquals(0, memberships.size(), "no WORKER membership");
      assertEquals(0, w.requestAuditRows(requestId).size(), "no audit row");
      assertEquals(0, w.notificationsAbout(requestId).stream()
          .filter(n -> n.getAudience() == NotificationAudience.USER).count(),
          "no decision notification");
      assertEquals(0.0, w.approvedCount() - approvedBefore, 1e-9, "nothing counted");
    }
    // the invariant after any interleaving (R5.1)
    assertTrue(linked.size() <= 1, "at most one linked ACTIVE employee: " + linked);
    assertTrue(memberships.size() <= 1, "at most one active WORKER membership: " + memberships);
  }

  private static List<Object> runTogether(List<Callable<Object>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<Object> call : calls) {
        futures.add(pool.submit(call));
      }
      List<Object> answers = new ArrayList<>();
      for (Future<Object> f : futures) {
        answers.add(f.get(60, TimeUnit.SECONDS));
      }
      return answers;
    } finally {
      pool.shutdownNow();
    }
  }

  // =============================================================================================
  // Property 30
  // =============================================================================================

  /** The injected failure: not a MongoDB error, so {@code MongoTx} does not retry it. */
  static final class InjectedFailure extends RuntimeException {
    InjectedFailure(String step) {
      super("injected failure after step " + step);
    }
  }

  @Property(tries = 40)
  void property30ApprovalIsAllOrNothing(@ForAll("faults") Fault fault) {
    World w = new World(db());
    String userId = "worker-1";
    String email = userId + "." + fault.emailLocal() + "@example.com";
    String shop = fault.shop();
    w.worker(userId, fault.name(), fault.phone(), email, null);
    String linkTarget = w.employee(shop, "L-1", "Link Target", null, null,
        EmployeeStatus.ACTIVE, null);
    String requestId = db.service.create(userId, shop).getId();
    String employeeId = fault.mode() == JoinApproveMode.LINK ? linkTarget : null;
    Statistics.label("fault").collect(fault.mode() + " after " + fault.failAfter());

    Map<String, List<Document>> before = w.snapshot();
    double approvedBefore = w.approvedCount();
    AtomicBoolean fired = new AtomicBoolean();
    Consumer<String> noOp = db.service.afterStep;
    db.service.afterStep = step -> {
      if (step.equals(fault.failAfter())) {
        fired.set(true);
        throw new InjectedFailure(step);
      }
    };
    try {
      db.service.approve(APPROVER, shop, requestId, fault.mode(), employeeId);
      fail("the injected failure must surface");
    } catch (InjectedFailure expected) {
      // the approval failed where we told it to
    } finally {
      db.service.afterStep = noOp;
    }
    assertTrue(fired.get(), "the approval reached step " + fault.failAfter());

    // nothing kept: every collection as before, the request PENDING, counters and metrics still
    Map<String, List<Document>> after = w.snapshot();
    assertEquals(before.keySet(), after.keySet(), "no new collection");
    for (String collection : before.keySet()) {
      assertEquals(before.get(collection), after.get(collection),
          collection + " is byte-identical after a failure after " + fault.failAfter());
    }
    WorkerJoinRequest request = db.template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.PENDING, request.getStatus(), "PENDING, not APPROVING");
    assertEquals(0.0, w.approvedCount() - approvedBefore, 1e-9, "no approval counted");

    // the retry without the fault succeeds as any approval does (Property 6)
    JoinRequestDecision d = db.service.approve(APPROVER, shop, requestId, fault.mode(),
        employeeId);
    JoinOutcome expected = fault.mode() == JoinApproveMode.LINK
        ? JoinOutcome.APPROVED_LINKED : JoinOutcome.APPROVED_CREATED;
    assertEquals(expected, d.getOutcome());
    request = db.template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.APPROVED, request.getStatus());
    assertEquals(d.getEmployeeId(), request.getEmployeeId());
    assertEquals(APPROVER, request.getDecidedByUserId());
    Employee employee = db.template.findById(d.getEmployeeId(), Employee.class);
    assertNotNull(employee);
    assertEquals(userId, employee.getUserId());
    assertEquals(EmployeeStatus.ACTIVE, employee.getStatus());
    assertEquals(shop, employee.getShopId());
    if (expected == JoinOutcome.APPROVED_CREATED) {
      assertEquals("EMP-001", employee.getCode(),
          "the first auto code: the failed attempt did not move the counter");
    }
    UserAccount account = db.template.findById(userId, UserAccount.class);
    List<UserShopMembership> worker = db.template.findAll(UserShopMembership.class).stream()
        .filter(m -> userId.equals(m.getUserId()) && m.getRole() == UserRole.WORKER
            && m.isActive())
        .toList();
    assertEquals(shop, account.getShopId(), "the worker's shop is claimed");
    assertEquals(1, worker.size(), "one WORKER membership");
    assertEquals(1, w.requestAuditRows(requestId).size(), "one audit row, from the success");
    assertEquals(1, w.notificationsAbout(requestId).stream()
        .filter(n -> n.getAudience() == NotificationAudience.USER).count(),
        "one notification, from the success");
    assertEquals(1.0, w.approvedCount() - approvedBefore, 1e-9, "counted once");
  }

  // =============================================================================================
  // the seeded world and its reads
  // =============================================================================================

  static final class World {
    final WorkerJoinMongoSupport db;

    World(WorkerJoinMongoSupport db) {
      this.db = db;
      db.reset();
    }

    void worker(String userId, String name, String phone, String email, String shopId) {
      account(userId, name, phone, email, UserRole.WORKER, shopId);
    }

    private void account(String userId, String name, String phone, String email, UserRole role,
        String shopId) {
      UserAccount a = new UserAccount();
      a.setUserId(userId);
      a.setName(name);
      a.setPhone(phone);
      a.setEmail(email);
      a.setRole(role);
      a.setShopId(shopId);
      a.setActive(true);
      a.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
      db.template.insert(a);
    }

    String employee(String shopId, String code, String name, String phone, String email,
        EmployeeStatus status, String userId) {
      Employee e = new Employee();
      e.setShopId(shopId);
      e.setCode(code);
      e.setName(name);
      e.setPhone(phone);
      e.setEmail(email);
      e.setStatus(status);
      e.setUserId(userId);
      e.setJoinDate("2025-01-15");
      e.setCreatedAt(Instant.parse("2025-01-15T00:00:00Z"));
      e.setCreatedByUserId(APPROVER);
      return db.template.insert(e).getId();
    }

    String shopName(String shopId) {
      return "Shop " + shopId;
    }

    List<HrAuditEvent> allAudit() {
      return db.template.findAll(HrAuditEvent.class);
    }

    /** Audit rows whose entity is the request itself. */
    List<HrAuditEvent> requestAuditRows(String requestId) {
      return allAudit().stream()
          .filter(a -> REQUEST_ENTITY.equals(a.getEntityType()) && requestId.equals(a.getEntityId()))
          .toList();
    }

    Document rawAudit(String id) {
      return db.template.getCollection("hr_audit_events")
          .find(new Document("_id", new ObjectId(id))).first();
    }

    List<HrNotification> allNotifications() {
      return db.template.findAll(HrNotification.class);
    }

    List<HrNotification> notificationsAbout(String requestId) {
      return allNotifications().stream()
          .filter(n -> requestId.equals(n.getJoinRequestId())).toList();
    }

    /** Approvals counted on {@code inventory_hr_worker_join_requests_total} so far (any shop). */
    double approvedCount() {
      return db.registry.find(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL).counters().stream()
          .filter(c -> String.valueOf(c.getId().getTag(HrMetricsConstants.TAG_EVENT))
              .startsWith("approved"))
          .mapToDouble(Counter::count)
          .sum();
    }

    /** Every collection, raw, sorted by {@code _id}. */
    Map<String, List<Document>> snapshot() {
      Map<String, List<Document>> all = new LinkedHashMap<>();
      for (String name : new TreeSet<>(db.template.getCollectionNames())) {
        List<Document> docs = new ArrayList<>();
        db.template.getCollection(name).find().sort(new Document("_id", 1)).into(docs);
        all.put(name, docs);
      }
      return all;
    }
  }
}
