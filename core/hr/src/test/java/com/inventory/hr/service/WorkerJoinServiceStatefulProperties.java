// Feature: worker-app, Property 4: Join-request state machine
// Feature: worker-app, Property 5: The snapshot is frozen and approval copies it
// Feature: worker-app, Property 6: Approval post-conditions
// Feature: worker-app, Property 8: Bulk actions partition the selection
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.util.PhoneNumbers;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.response.BulkJoinResult;
import com.inventory.hr.rest.dto.response.JoinRequestDecision;
import com.inventory.hr.rest.dto.response.JoinRequestSelfResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserPhoto;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.service.UserShopMembershipService;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.ActionChainArbitrary;
import net.jqwik.api.state.Transformer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;

/**
 * The join-request life cycle as a stateful property (worker-app design, task 4.7): jqwik draws a
 * chain of operations (a worker files or withdraws a request, a phone files a link request, a
 * manager approves one way or the other, rejects, or decides in bulk, a worker edits their profile
 * or changes their photo) and applies each to an in-memory model <em>and</em> to the real {@link
 * WorkerJoinService} over a real MongoDB replica set ({@link WorkerJoinMongoSupport}). After every
 * step the database must agree with the model row for row: requests, accounts, employees,
 * memberships, and the pairings and punches that nothing here may touch. The model is written from
 * the requirements, not from the service, so a disagreement is a finding, not a modelling choice.
 *
 * <p>The database is real because two rules live there and nowhere else: the partial unique index
 * {@code user_open_unique} (one open request per account, R3.5) and the approval transaction
 * (R14.4), whose rollback is what leaves "nothing changed" true after a refusal inside it.
 *
 * <p><b>Property 4 — Validates: Requirements 3.5, 3.6, 3.7, 4.10.</b> For any chain of {@code
 * create}, {@code withdraw}, {@code approve} and {@code reject} by any account against any shop:
 * an account never has more than one open request; {@code create} is refused with {@code
 * HR_WORKER_ONLY} for a staff account, with {@code HR_WORKER_HAS_SHOP} when the account has a shop
 * or a linked ACTIVE employee and with {@code HR_WORKER_REQUEST_PENDING} when one is open; {@code
 * approve}, {@code reject} and {@code withdraw} succeed only on a {@code PENDING} request and
 * otherwise answer {@code HR_REQUEST_ALREADY_HANDLED} and change nothing.
 *
 * <p><b>Property 5 — Validates: Requirements 3.4, 3.9, 8.3.</b> For any account and any profile
 * edits or photo changes made after a request was filed, the request's snapshot still equals the
 * account as it was at filing (name, phone, e-mail, {@code hasPhoto}), {@code requestedAt} is the
 * server clock at filing, the snapshot has no message field; approving with {@code CREATE} gives an
 * employee whose name, phone and e-mail equal the snapshot, not the account's current values; and
 * no other request, pairing or punch changes because of the edit.
 *
 * <p><b>Property 6 — Validates: Requirements 4.5, 4.8, 4.9.</b> After {@code
 * approve(CREATE)} there is one new employee in the shop, {@code ACTIVE}, {@code joinDate} the
 * approval date in Asia/Kolkata, the next {@code EMP-nnn} code, {@code userId} the requester,
 * every other field empty; a {@code WORKER} membership for the requester; the request {@code
 * APPROVED} with {@code APPROVED_CREATED} and that employee. {@code approve(LINK, e)} succeeds iff
 * {@code e} is {@code ACTIVE} and {@code e.userId ∈ {null, requester}}, otherwise answers {@code
 * HR_MATCH_LINKED_ELSEWHERE} or {@code HR_MATCH_EXITED} and leaves the request {@code PENDING}; on
 * success {@code e} equals its previous document except {@code userId}, {@code updatedAt}, {@code
 * updatedByUserId} (and the optimistic-lock counter {@code revision} every save bumps), the
 * membership is {@code WORKER}, every pairing and punch is byte-identical, and the request is
 * {@code APPROVED} with {@code APPROVED_LINKED}. After {@code reject}, employees and pairings are
 * unchanged. Staff memberships are never touched: a staff account cannot file a request (R5.6),
 * and the chains try one so the refusal is seen.
 *
 * <p><b>Property 8 — Validates: Requirements 4.6.</b> For any selection of requests, {@code
 * bulkApprove} answers {@code approved}, {@code skipped} and {@code failed} whose ids partition the
 * selection; {@code skipped} is exactly the pending ones with a possible match, each still {@code
 * PENDING}; the approved ones are now {@code APPROVED}; and {@code bulkReject(ids, reason)} leaves
 * every pending id {@code REJECTED} with the one normalised reason.
 */
@AddLifecycleHook(value = WorkerJoinMongoSupport.RequiresReplicaSet.class,
    propagateTo = PropagationMode.ALL_DESCENDANTS)
class WorkerJoinServiceStatefulProperties {

  static final String SHOP_1 = "shop-1";
  static final String SHOP_2 = "shop-2";
  static final List<String> SHOPS = List.of(SHOP_1, SHOP_2);
  static final String APPROVER = "manager-1";
  /** An account that is nobody in this world: the employees linked to it are "linked elsewhere". */
  static final String FOREIGN_LOGIN = "foreign-login";
  static final List<String> WORKERS = List.of("w1", "w2", "w3");
  /** A CASHIER of shop-1: never files a request (HR_WORKER_ONLY, R5.6). */
  static final String STAFF = "s1";
  static final String NONE = "none";

  /** Reasons a rejection may carry: blank, real, padded, one over the limit; null is injected. */
  static final List<String> REASONS = List.of("", "   ", "Not hiring now",
      "  Try again next month  ", "x".repeat(WorkerJoinService.MAX_REASON_LENGTH + 1));
  /** Profile edits: valid names and phones, and one invalid of each (refused, nothing changes). */
  static final List<String> NAMES = List.of("Priya Sharma", " Ravi  Kumar ", "Kavya Nair", "A", "");
  static final List<String> PHONES = List.of("9876501234", "+91 98765 01235", "91-9876501236",
      "12345");

  /** Raw-document fields a {@code CREATE} approval may set; anything else is "not empty" (R4.8). */
  static final Set<String> CREATED_EMPLOYEE_FIELDS = Set.of("_id", "_class", "shopId", "code",
      "name", "phone", "email", "userId", "joinDate", "status", "salaryRevisions", "revision",
      "createdAt", "createdByUserId", "updatedAt", "updatedByUserId");
  /** What a link may change on the employee: the link itself, the update stamps, the version. */
  static final Set<String> LINK_MAY_CHANGE = Set.of("userId", "updatedAt", "updatedByUserId",
      "revision");

  private static WorkerJoinMongoSupport db;

  private static synchronized WorkerJoinMongoSupport db() {
    if (db == null) {
      db = new WorkerJoinMongoSupport("stateful");
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
  // properties
  // =============================================================================================

  @Property(tries = 40)
  void property4TheStateMachineHoldsUnderAnyChainOfOperations(
      @ForAll("stateMachineChains") ActionChain<Scenario> chain) {
    chain.withInvariant("database agrees with the model", Scenario::assertDatabaseAgrees)
        .withInvariant("one open request per account", Scenario::assertOneOpenRequestPerAccount)
        .run();
  }

  @Provide
  Arbitrary<ActionChain<Scenario>> stateMachineChains() {
    return chains()
        .withAction(5, CREATE)
        .withAction(2, WITHDRAW)
        .withAction(4, APPROVE)
        .withAction(2, REJECT);
  }

  @Property(tries = 40)
  void property5TheSnapshotIsFrozenAndApprovalCopiesIt(
      @ForAll("snapshotChains") ActionChain<Scenario> chain) {
    chain.withInvariant("database agrees with the model", Scenario::assertDatabaseAgrees)
        .withInvariant("snapshots are as at filing", Scenario::assertSnapshotsFrozen)
        .run();
  }

  @Provide
  Arbitrary<ActionChain<Scenario>> snapshotChains() {
    return chains()
        .withAction(3, CREATE)
        .withAction(3, EDIT_PROFILE)
        .withAction(2, TOGGLE_PHOTO)
        .withAction(3, APPROVE_CREATE)
        .withAction(1, WITHDRAW);
  }

  /** R3.4: the request holds account, shop, snapshot and time, and nothing the worker typed. */
  @Example
  void property5TheSnapshotHasNoMessageField() {
    Set<String> fields = Arrays.stream(WorkerJoinRequest.Snapshot.class.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
        .map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(new TreeSet<>(List.of("name", "phone", "email", "hasPhoto")), fields);
    Set<String> rowFields = Arrays.stream(WorkerJoinRequest.class.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
        .map(Field::getName)
        .collect(Collectors.toSet());
    for (String free : List.of("message", "note", "comment", "text")) {
      assertFalse(rowFields.contains(free), "a request has no free-text field: " + free);
    }
  }

  @Property(tries = 40)
  void property6ApprovalPostConditionsHoldForCreateLinkAndReject(
      @ForAll("approvalChains") ActionChain<Scenario> chain) {
    chain.withInvariant("database agrees with the model", Scenario::assertDatabaseAgrees)
        .withInvariant("pairings and punches are untouched",
            Scenario::assertPairingsAndPunchesUntouched)
        .run();
  }

  @Provide
  Arbitrary<ActionChain<Scenario>> approvalChains() {
    return chains()
        .withAction(4, CREATE)
        .withAction(5, APPROVE)
        .withAction(2, REJECT);
  }

  @Property(tries = 40)
  void property8BulkActionsPartitionTheSelection(
      @ForAll("bulkChains") ActionChain<Scenario> chain) {
    chain.withInvariant("database agrees with the model", Scenario::assertDatabaseAgrees)
        .run();
  }

  @Provide
  Arbitrary<ActionChain<Scenario>> bulkChains() {
    return chains()
        .withAction(5, CREATE)
        .withAction(3, BULK_APPROVE)
        .withAction(2, BULK_REJECT)
        .withAction(1, REJECT);
  }

  private static ActionChainArbitrary<Scenario> chains() {
    return ActionChain.startWith(() -> new Scenario(db())).withMaxTransformations(15);
  }

  // =============================================================================================
  // actions: each draws its arguments from the current model and applies to model + service
  // =============================================================================================

  static final Arbitrary<String> SHOP = Arbitraries.of(SHOPS);
  static final Arbitrary<String> WORKER = Arbitraries.of(WORKERS);
  /** Mostly workers; the staff account now and then (always refused, HR_WORKER_ONLY). */
  static final Arbitrary<String> ACCOUNT = Arbitraries.frequencyOf(
      Tuple.of(4, WORKER), Tuple.of(1, Arbitraries.just(STAFF)));
  static final Arbitrary<String> REASON = Arbitraries.of(REASONS).injectNull(0.2);

  static final Action.Independent<Scenario> CREATE = () ->
      Combinators.combine(ACCOUNT, SHOP).as((user, shop) ->
          Transformer.<Scenario>mutate("create " + user + " → " + shop, s -> s.create(user, shop)));

  /** The request's own account four times in five, so most withdrawals land on an own row. */
  static final Action.Dependent<Scenario> WITHDRAW = state ->
      request(state).flatMap(req -> {
        Req r = state.requests.get(req);
        Arbitrary<String> owner = r == null ? ACCOUNT
            : Arbitraries.frequencyOf(Tuple.of(4, Arbitraries.just(r.userId)),
                Tuple.of(1, ACCOUNT));
        return owner.map(user -> Transformer.<Scenario>mutate("withdraw " + user + " " + req,
            s -> s.withdraw(user, req)));
      });

  /**
   * Either mode, in the request's own shop four times in five; a {@code LINK} names an employee of
   * that shop three times in four, else any employee (the other shop's, exited or linked included).
   */
  static final Action.Dependent<Scenario> APPROVE = state ->
      requestInShop(state).flatMap(pick -> {
        List<String> here = state.employeeLabels(pick.get2());
        Arbitrary<String> employee = Arbitraries.frequencyOf(
            Tuple.of(3, Arbitraries.of(here)), Tuple.of(1, Arbitraries.of(state.employeeLabels())));
        return Combinators.combine(Arbitraries.of(JoinApproveMode.class), employee)
            .as((mode, emp) -> {
              String target = mode == JoinApproveMode.LINK ? emp : null;
              return Transformer.<Scenario>mutate("approve " + pick.get2() + " " + pick.get1() + " "
                  + mode + (target != null ? " " + target : ""),
                  s -> s.approve(pick.get2(), pick.get1(), mode, target));
            });
      });

  static final Action.Dependent<Scenario> APPROVE_CREATE = state ->
      requestInShop(state).map(pick -> Transformer.<Scenario>mutate(
          "approve " + pick.get2() + " " + pick.get1() + " CREATE",
          s -> s.approve(pick.get2(), pick.get1(), JoinApproveMode.CREATE, null)));

  static final Action.Dependent<Scenario> REJECT = state ->
      Combinators.combine(requestInShop(state), REASON).as((pick, reason) ->
          Transformer.<Scenario>mutate(
              "reject " + pick.get2() + " " + pick.get1() + " reason=" + show(reason),
              s -> s.reject(pick.get2(), pick.get1(), reason)));

  static final Action.Dependent<Scenario> BULK_APPROVE = state ->
      Combinators.combine(SHOP, selection(state)).as((shop, labels) ->
          Transformer.<Scenario>mutate("bulkApprove " + shop + " " + labels,
              s -> s.bulkApprove(shop, labels)));

  static final Action.Dependent<Scenario> BULK_REJECT = state ->
      Combinators.combine(SHOP, selection(state), REASON).as((shop, labels, reason) ->
          Transformer.<Scenario>mutate(
              "bulkReject " + shop + " " + labels + " reason=" + show(reason),
              s -> s.bulkReject(shop, labels, reason)));

  static final Action.Independent<Scenario> EDIT_PROFILE = () ->
      Combinators.combine(WORKER, Arbitraries.of(NAMES), Arbitraries.of(PHONES))
          .as((user, name, phone) -> Transformer.<Scenario>mutate(
              "editProfile " + user + " name=" + show(name) + " phone=" + phone,
              s -> s.editProfile(user, name, phone)));

  static final Action.Independent<Scenario> TOGGLE_PHOTO = () ->
      WORKER.map(user -> Transformer.<Scenario>mutate("togglePhoto " + user,
          s -> s.togglePhoto(user)));

  /** A filed request nine times in ten, {@link #NONE} (never a row) otherwise. */
  private static Arbitrary<String> request(Scenario state) {
    if (state.requests.isEmpty()) {
      return Arbitraries.just(NONE);
    }
    return Arbitraries.frequencyOf(
        Tuple.of(9, Arbitraries.of(new ArrayList<>(state.requests.keySet()))),
        Tuple.of(1, Arbitraries.just(NONE)));
  }

  /** A request and the shop an approver acts in: the request's own four times in five. */
  private static Arbitrary<Tuple.Tuple2<String, String>> requestInShop(Scenario state) {
    return request(state).flatMap(req -> {
      Req r = state.requests.get(req);
      Arbitrary<String> shop = r == null ? SHOP
          : Arbitraries.frequencyOf(Tuple.of(4, Arbitraries.just(r.shopId)), Tuple.of(1, SHOP));
      return shop.map(s -> Tuple.of(req, s));
    });
  }

  /** One to five picks, duplicates and a missing id allowed: the service must cope (R4.6). */
  private static Arbitrary<List<String>> selection(Scenario state) {
    return request(state).list().ofMinSize(1).ofMaxSize(5);
  }

  private static String show(String s) {
    if (s == null) {
      return "null";
    }
    return s.length() > 24
        ? "\"" + s.substring(0, 12) + "…\"(" + s.length() + ")"
        : "\"" + s + "\"";
  }

  // =============================================================================================
  // the model
  // =============================================================================================

  /** An account as the user module holds it. */
  static final class Acct {
    final String userId;
    String name;
    String phone;
    final String email;
    final UserRole role;
    String shopId;
    boolean photo;

    Acct(String userId, String name, String phone, String email, UserRole role, String shopId) {
      this.userId = userId;
      this.name = name;
      this.phone = phone;
      this.email = email;
      this.role = role;
      this.shopId = shopId;
    }

    boolean worker() {
      return role == UserRole.WORKER;
    }
  }

  /** An employee; {@code id} is the database id, {@code label} how an action names it. */
  static final class Emp {
    final String label;
    final String id;
    final String shopId;
    final String code;
    final String name;
    final String phone;
    final String email;
    final EmployeeStatus status;
    String userId;

    Emp(String label, String id, String shopId, String code, String name, String phone,
        String email, EmployeeStatus status, String userId) {
      this.label = label;
      this.id = id;
      this.shopId = shopId;
      this.code = code;
      this.name = name;
      this.phone = phone;
      this.email = email;
      this.status = status;
      this.userId = userId;
    }
  }

  /** A request as filed; {@code id} is the database id, {@code label} how an action names it. */
  static final class Req {
    final String label;
    final String id;
    final String shopId;
    final String userId;
    final WorkerJoinRequest.Snapshot snapshot;
    final Instant requestedAt;
    WorkerJoinStatus status = WorkerJoinStatus.PENDING;
    JoinOutcome outcome;
    String employeeId;
    String decidedByUserId;
    String rejectionReason;

    Req(String label, String id, String shopId, String userId,
        WorkerJoinRequest.Snapshot snapshot, Instant requestedAt) {
      this.label = label;
      this.id = id;
      this.shopId = shopId;
      this.userId = userId;
      this.snapshot = snapshot;
      this.requestedAt = requestedAt;
    }

    boolean open() {
      return WorkerJoinService.OPEN.contains(status);
    }
  }

  /** A refusal the model predicts: the error code and the exact message. */
  record Refusal(ErrorCode code, String message) {
    static Refusal of(ErrorCode code) {
      return new Refusal(code, code.getMessage());
    }

    static Refusal of(ErrorCode code, String formatArgument) {
      return new Refusal(code, String.format(code.getMessage(), formatArgument));
    }

    static Refusal notFound(String resource, String id) {
      return new Refusal(ErrorCode.RESOURCE_NOT_FOUND,
          String.format("%s not found with %s: %s", resource, "id", id));
    }

    static Refusal validation(String message) {
      return new Refusal(ErrorCode.VALIDATION_ERROR, message);
    }
  }

  /**
   * The model and the system under test, side by side. Every action applies the requirement to the
   * model, calls the service, and checks the answer; the invariants then compare the whole
   * database with the model.
   */
  static final class Scenario {
    final WorkerJoinMongoSupport db;
    final Map<String, Acct> accounts = new LinkedHashMap<>();
    final Map<String, Emp> employees = new LinkedHashMap<>();
    final Map<String, Req> requests = new LinkedHashMap<>();
    /** Active WORKER memberships as {@code userId|shopId}. */
    final Set<String> workerMemberships = new TreeSet<>();
    /** The next auto code per shop ({@code EMP-nnn}); the seeded codes are hand-written. */
    final Map<String, Integer> nextCode = new LinkedHashMap<>();
    /** Collections nothing in this feature may change, as seeded. */
    final Map<String, List<Document>> frozen = new LinkedHashMap<>();
    /** Raw staff membership rows as seeded (R13.4: a staff membership is never touched). */
    final List<Document> staffMemberships;
    final List<String> steps = new ArrayList<>();
    int nextRequest = 1;

    Scenario(WorkerJoinMongoSupport db) {
      this.db = db;
      db.reset();
      // accounts
      int i = 0;
      for (String w : WORKERS) {
        account(w, "Worker " + w.toUpperCase(Locale.ROOT), "98765432" + i + "0",
            w + "@example.com", UserRole.WORKER, null);
        i++;
      }
      account(STAFF, "Staff One", "9123456789", STAFF + "@example.com", UserRole.CASHIER, SHOP_1);
      UserShopMembership staff = new UserShopMembership();
      staff.setUserId(STAFF);
      staff.setShopId(SHOP_1);
      staff.setRole(UserRole.CASHIER);
      staff.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
      staff.setActive(true);
      staff.setJoinedAt(Instant.parse("2025-01-01T00:00:00Z"));
      db.template.insert(staff);
      staffMemberships = raw("user_shop_memberships");
      // employees: plain, a possible match for a worker, an exited one, one linked elsewhere
      for (String shop : SHOPS) {
        nextCode.put(shop, 1);
      }
      employee("e1a", SHOP_1, "OLD-1", "Plain One", "9000000001", "plain1@example.com",
          EmployeeStatus.ACTIVE, null);
      employee("e1m", SHOP_1, "OLD-2", "Match W1", "+91 98765 43200", null,
          EmployeeStatus.ACTIVE, null);
      employee("e1x", SHOP_1, "OLD-3", "Exited One", "9000000003", null,
          EmployeeStatus.EXITED, null);
      employee("e1f", SHOP_1, "OLD-4", "Foreign One", "9000000004", null,
          EmployeeStatus.ACTIVE, FOREIGN_LOGIN);
      employee("e2a", SHOP_2, "OLD-1", "Plain Two", "9000000011", "plain2@example.com",
          EmployeeStatus.ACTIVE, null);
      employee("e2m", SHOP_2, "OLD-2", "Match W2", null, " W2@Example.COM ",
          EmployeeStatus.ACTIVE, null);
      employee("e2x", SHOP_2, "OLD-3", "Exited Two", "9000000013", null,
          EmployeeStatus.EXITED, null);
      employee("e2f", SHOP_2, "OLD-4", "Foreign Two", "9000000014", null,
          EmployeeStatus.ACTIVE, FOREIGN_LOGIN);
      // every seeded employee has an ACTIVE pairing and a punch; neither may change
      for (Emp e : employees.values()) {
        DevicePairing pairing = new DevicePairing();
        pairing.setId(pairingOf(e));
        pairing.setShopId(e.shopId);
        pairing.setEmployeeId(e.id);
        pairing.setStatus(PairingStatus.ACTIVE);
        pairing.setPairedAt(Instant.parse("2025-02-01T03:30:00Z"));
        pairing.setPairedBy(APPROVER);
        db.template.insert(pairing);
        db.template.getCollection("hr_punches").insertOne(new Document("_id", "punch-" + e.label)
            .append("shopId", e.shopId).append("employeeId", e.id).append("pairingId", pairingOf(e))
            .append("dedupeKey", "dedupe-" + e.label)
            .append("direction", "IN").append("at", Instant.parse("2025-02-02T03:30:00Z")));
      }
      frozen.put("hr_device_pairings", raw("hr_device_pairings"));
      frozen.put("hr_punches", raw("hr_punches"));
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
      accounts.put(userId, new Acct(userId, name, phone, email, role, shopId));
    }

    private void employee(String label, String shopId, String code, String name, String phone,
        String email, EmployeeStatus status, String userId) {
      Employee e = new Employee();
      e.setShopId(shopId);
      e.setCode(code);
      e.setName(name);
      e.setPhone(phone);
      e.setEmail(email);
      e.setStatus(status);
      e.setUserId(userId);
      e.setJoinDate("2025-01-15");
      if (status == EmployeeStatus.EXITED) {
        e.setExitDate("2025-03-31");
      }
      e.setCreatedAt(Instant.parse("2025-01-15T00:00:00Z"));
      e.setCreatedByUserId(APPROVER);
      Employee saved = db.template.insert(e);
      employees.put(label, new Emp(label, saved.getId(), shopId, code, name, phone, email, status,
          userId));
    }

    static String pairingOf(Emp e) {
      return "pairing-" + e.label;
    }

    List<String> employeeLabels() {
      return new ArrayList<>(employees.keySet());
    }

    List<String> employeeLabels(String shopId) {
      return employees.values().stream().filter(e -> e.shopId.equals(shopId)).map(e -> e.label)
          .toList();
    }

    private Req req(String label) {
      return requests.get(label);
    }

    private String requestId(String label) {
      Req r = requests.get(label);
      return r != null ? r.id : "000000000000000000000000";
    }

    private Emp empById(String id) {
      return employees.values().stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    private Optional<Req> openRequestOf(String userId) {
      return requests.values().stream().filter(r -> r.userId.equals(userId) && r.open())
          .findFirst();
    }

    private boolean hasShop(Acct a) {
      return a.shopId != null || employees.values().stream()
          .anyMatch(e -> a.userId.equals(e.userId) && e.status == EmployeeStatus.ACTIVE);
    }

    private WorkerJoinRequest.Snapshot snapshotOf(Acct a) {
      return new WorkerJoinRequest.Snapshot(a.name.trim(),
          PhoneNumbers.normalise(a.phone).orElse(a.phone),
          a.email.trim().toLowerCase(Locale.ROOT), a.photo);
    }

    // ---- worker side ---------------------------------------------------------------------------

    void create(String userId, String shopId) {
      Acct a = accounts.get(userId);
      Refusal refusal = null;
      if (!a.worker()) {
        refusal = Refusal.of(ErrorCode.HR_WORKER_ONLY);
      } else if (hasShop(a)) {
        refusal = Refusal.of(ErrorCode.HR_WORKER_HAS_SHOP);
      } else if (openRequestOf(userId).isPresent()) {
        refusal = Refusal.of(ErrorCode.HR_WORKER_REQUEST_PENDING);
      }
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      JoinRequestSelfResponse answer =
          call("create", () -> db.service.create(userId, shopId), refusal);
      if (answer == null) {
        return;
      }
      file(answer, shopId, userId, snapshotOf(a), before);
    }

    private void file(JoinRequestSelfResponse answer, String shopId, String userId,
        WorkerJoinRequest.Snapshot snapshot, Instant before) {
      Instant after = Instant.now();
      assertNotNull(answer.getId());
      assertEquals(WorkerJoinStatus.PENDING, answer.getStatus());
      assertEquals("Shop " + shopId, answer.getShopName());
      WorkerJoinRequest row = db.template.findById(answer.getId(), WorkerJoinRequest.class);
      assertNotNull(row, "the request was inserted");
      Instant requestedAt = row.getRequestedAt();
      assertNotNull(requestedAt);
      assertFalse(requestedAt.isBefore(before) || requestedAt.isAfter(after),
          "requestedAt is the server clock at filing: " + requestedAt + " in [" + before + ", "
              + after + "]");
      // the answer carries the JVM instant (nanoseconds); the row keeps MongoDB's milliseconds
      assertEquals(requestedAt, answer.getRequestedAt().truncatedTo(ChronoUnit.MILLIS));
      String label = "r" + nextRequest++;
      requests.put(label, new Req(label, answer.getId(), shopId, userId, snapshot, requestedAt));
      steps.add("filed " + label + " = " + answer.getId());
    }

    void withdraw(String userId, String label) {
      Req r = req(label);
      Refusal refusal = null;
      if (r == null || !r.userId.equals(userId)) {
        refusal = Refusal.notFound("Join request", requestId(label));
      } else if (r.status != WorkerJoinStatus.PENDING) {
        refusal = Refusal.of(ErrorCode.HR_REQUEST_ALREADY_HANDLED);
      }
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      JoinRequestSelfResponse answer =
          call("withdraw", () -> db.service.withdraw(userId, requestId(label)), refusal);
      if (answer == null) {
        return;
      }
      assertEquals(WorkerJoinStatus.WITHDRAWN, answer.getStatus());
      r.status = WorkerJoinStatus.WITHDRAWN;
      r.outcome = JoinOutcome.WITHDRAWN;
      r.decidedByUserId = userId;
      assertDecidedBetween(r, before, Instant.now());
    }

    // ---- manager side --------------------------------------------------------------------------

    void approve(String shopId, String label, JoinApproveMode mode, String employeeLabel) {
      Req r = req(label);
      String employeeId = employeeLabel != null ? employees.get(employeeLabel).id : null;
      Approval plan = planApproval(shopId, r, label, mode, employeeId);
      Document linkedBefore = plan.linkTarget != null ? rawEmployee(plan.linkTarget.id) : null;
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      JoinRequestDecision decision = call("approve",
          () -> db.service.approve(APPROVER, shopId, requestId(label), mode, employeeId),
          plan.refusal);
      if (decision == null) {
        return;
      }
      Instant after = Instant.now();
      applyApproval(plan, r, shopId, decision, linkedBefore, before, after);
    }

    /** What the requirements say an approval does, decided before the call. */
    static final class Approval {
      Refusal refusal;
      JoinApproveMode effectiveMode;
      Emp linkTarget;
      boolean worker;
    }

    private Approval planApproval(String shopId, Req r, String label, JoinApproveMode mode,
        String employeeId) {
      Approval p = new Approval();
      if (r == null || !r.shopId.equals(shopId)) {
        p.refusal = Refusal.notFound("Join request", requestId(label));
        return p;
      }
      if (r.status != WorkerJoinStatus.PENDING) {
        p.refusal = Refusal.of(ErrorCode.HR_REQUEST_ALREADY_HANDLED);
        return p;
      }
      p.effectiveMode = mode;
      String target = employeeId;
      Acct a = accounts.get(r.userId);
      p.worker = a.worker();
      if (a.worker()) {
        boolean elsewhere = (a.shopId != null && !shopId.equals(a.shopId))
            || employees.values().stream().anyMatch(e -> r.userId.equals(e.userId)
                && e.status == EmployeeStatus.ACTIVE && !shopId.equals(e.shopId));
        if (elsewhere) {
          p.refusal = Refusal.of(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP);
          return p;
        }
      }
      // the shop_user_unique index: a login links to at most one employee per shop, any status
      boolean linkedHereAlready = employees.values().stream().anyMatch(e ->
          shopId.equals(e.shopId) && r.userId.equals(e.userId));
      if (p.effectiveMode == JoinApproveMode.CREATE) {
        if (linkedHereAlready) {
          p.refusal = Refusal.of(ErrorCode.HR_LOGIN_ALREADY_LINKED);
        }
        return p;
      }
      Emp e = empById(target);
      if (e == null || !e.shopId.equals(shopId)) {
        p.refusal = Refusal.notFound("Employee", target);
        return p;
      }
      p.linkTarget = e;
      if (e.status != EmployeeStatus.ACTIVE) {
        p.refusal = Refusal.of(ErrorCode.HR_MATCH_EXITED, e.code);
      } else if (e.userId != null && !e.userId.equals(r.userId)) {
        p.refusal = Refusal.of(ErrorCode.HR_MATCH_LINKED_ELSEWHERE, e.code);
      } else if (e.userId == null && linkedHereAlready) {
        p.refusal = Refusal.of(ErrorCode.HR_LOGIN_ALREADY_LINKED);
      }
      return p;
    }

    private void applyApproval(Approval p, Req r, String shopId, JoinRequestDecision decision,
        Document linkedBefore, Instant before, Instant after) {
      assertEquals(r.id, decision.getId());
      assertNotNull(decision.getEmployeeId(), "an approval names its employee");
      Acct a = accounts.get(r.userId);
      if (p.effectiveMode == JoinApproveMode.CREATE) {
        assertEquals(JoinOutcome.APPROVED_CREATED, decision.getOutcome());
        Employee created = db.template.findById(decision.getEmployeeId(), Employee.class);
        assertNotNull(created, "one new employee");
        assertEquals(shopId, created.getShopId());
        assertEquals(EmployeeStatus.ACTIVE, created.getStatus());
        assertEquals(HrDateUtils.format(HrDateUtils.today()), created.getJoinDate(),
            "joinDate is the approval date in Asia/Kolkata");
        String code = String.format("EMP-%03d", nextCode.get(shopId));
        assertEquals(code, created.getCode(), "the shop's next auto code");
        nextCode.put(shopId, nextCode.get(shopId) + 1);
        assertEquals(r.userId, created.getUserId());
        assertEquals(r.snapshot.getName(), created.getName(), "name from the snapshot (R3.9)");
        assertEquals(r.snapshot.getPhone(), created.getPhone(), "phone from the snapshot (R3.9)");
        assertEquals(r.snapshot.getEmail(), created.getEmail(), "e-mail from the snapshot (R3.9)");
        Document rawCreated = rawEmployee(created.getId());
        Set<String> extra = new TreeSet<>(rawCreated.keySet());
        extra.removeAll(CREATED_EMPLOYEE_FIELDS);
        assertTrue(extra.isEmpty(), "every other field empty; found " + extra);
        assertEquals(List.of(), rawCreated.get("salaryRevisions"), "no pay yet");
        String label = "e" + shopId.charAt(shopId.length() - 1) + "n" + created.getCode()
            .substring(4);
        employees.put(label, new Emp(label, created.getId(), shopId, created.getCode(),
            r.snapshot.getName(), r.snapshot.getPhone(), r.snapshot.getEmail(),
            EmployeeStatus.ACTIVE, r.userId));
      } else {
        assertEquals(JoinOutcome.APPROVED_LINKED, decision.getOutcome());
        Emp e = p.linkTarget;
        assertEquals(e.id, decision.getEmployeeId(), "the paired or chosen employee");
        Document linkedAfter = rawEmployee(e.id);
        assertEquals(r.userId, linkedAfter.getString("userId"));
        Set<String> changed = changedKeys(linkedBefore, linkedAfter);
        changed.removeAll(LINK_MAY_CHANGE);
        assertTrue(changed.isEmpty(), "a link changes only userId and the update stamps; also "
            + changed);
        e.userId = r.userId;
      }
      if (p.worker) {
        a.shopId = shopId;
        workerMemberships.add(r.userId + "|" + shopId);
      }
      r.status = WorkerJoinStatus.APPROVED;
      r.outcome = decision.getOutcome();
      r.employeeId = decision.getEmployeeId();
      r.decidedByUserId = APPROVER;
      assertDecidedBetween(r, before, after);
    }

    void reject(String shopId, String label, String reason) {
      Req r = req(label);
      String clean = cleanReason(reason);
      Refusal refusal = null;
      if (clean != null && clean.length() > WorkerJoinService.MAX_REASON_LENGTH) {
        refusal = Refusal.validation(WorkerJoinService.REASON_TOO_LONG);
      } else if (r == null || !r.shopId.equals(shopId)) {
        refusal = Refusal.notFound("Join request", requestId(label));
      } else if (r.status != WorkerJoinStatus.PENDING) {
        refusal = Refusal.of(ErrorCode.HR_REQUEST_ALREADY_HANDLED);
      }
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      JoinRequestDecision decision =
          call("reject", () -> db.service.reject(APPROVER, shopId, requestId(label), reason),
              refusal);
      if (decision == null) {
        return;
      }
      assertEquals(r.id, decision.getId());
      assertEquals(JoinOutcome.REJECTED, decision.getOutcome());
      assertNull(decision.getEmployeeId());
      r.status = WorkerJoinStatus.REJECTED;
      r.outcome = JoinOutcome.REJECTED;
      r.rejectionReason = clean;
      r.decidedByUserId = APPROVER;
      assertDecidedBetween(r, before, Instant.now());
    }

    void bulkApprove(String shopId, List<String> labels) {
      List<String> ids = labels.stream().map(this::requestId).toList();
      List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
      // the service reads the match candidates once, before the first decision
      List<Emp> candidates = employees.values().stream().filter(e -> shopId.equals(e.shopId))
          .toList();
      Map<String, Req> byId = new LinkedHashMap<>();
      requests.values().forEach(r -> byId.put(r.id, r));
      Set<String> expectApproved = new LinkedHashSet<>();
      Set<String> expectSkipped = new LinkedHashSet<>();
      Map<String, String> expectFailed = new LinkedHashMap<>();
      Map<String, Approval> plans = new LinkedHashMap<>();
      for (String id : unique) {
        Req r = byId.get(id);
        if (r == null || !r.shopId.equals(shopId)) {
          expectFailed.put(id, Refusal.notFound("Join request", id).message());
          continue;
        }
        if (r.status == WorkerJoinStatus.PENDING && possibleMatch(r, candidates)) {
          expectSkipped.add(id);
          continue;
        }
        Approval plan = planApproval(shopId, r, r.label, JoinApproveMode.CREATE, null);
        if (plan.refusal != null) {
          expectFailed.put(id, plan.refusal.message());
          continue;
        }
        plans.put(id, plan);
        expectApproved.add(id);
        previewApproval(plan, r);
      }
      Map<String, Document> linkedBefore = new LinkedHashMap<>();
      for (Approval plan : plans.values()) {
        if (plan.linkTarget != null) {
          linkedBefore.put(plan.linkTarget.id, rawEmployee(plan.linkTarget.id));
        }
      }
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      BulkJoinResult result =
          call("bulkApprove", () -> db.service.bulkApprove(APPROVER, shopId, ids), null);
      Instant after = Instant.now();

      Set<String> skipped = result.getSkipped().stream().map(BulkJoinResult.Skipped::getId)
          .collect(Collectors.toCollection(LinkedHashSet::new));
      Map<String, String> failed = new LinkedHashMap<>();
      result.getFailed().forEach(f -> failed.put(f.getId(), f.getMessage()));
      Statistics.label("bulkApprove partition").collect(
          "approved=" + (result.getApproved() > 0) + " skipped=" + !skipped.isEmpty()
              + " failed=" + !failed.isEmpty());
      assertEquals(expectApproved.size(), result.getApproved(), "approved count");
      assertEquals(expectSkipped, skipped, "skipped = exactly the pending ones with a match");
      for (BulkJoinResult.Skipped s : result.getSkipped()) {
        assertEquals(BulkJoinResult.SkipReason.POSSIBLE_MATCH, s.getReason());
      }
      assertEquals(expectFailed, failed, "failed ids and their texts");
      assertEquals(unique.size(), result.getApproved() + skipped.size() + failed.size(),
          "approved + skipped + failed partition the selection");
      assertTrue(unique.containsAll(skipped) && unique.containsAll(failed.keySet()));
      // the approved ones: find them by their new state, and check each as a single approval
      for (Map.Entry<String, Approval> entry : plans.entrySet()) {
        Req r = byId.get(entry.getKey());
        WorkerJoinRequest row = db.template.findById(r.id, WorkerJoinRequest.class);
        assertEquals(WorkerJoinStatus.APPROVED, row.getStatus(), r.label + " is now APPROVED");
        JoinRequestDecision decision =
            new JoinRequestDecision(row.getId(), row.getOutcome(), row.getEmployeeId());
        Approval plan = entry.getValue();
        applyApproval(plan, r, shopId, decision,
            plan.linkTarget != null ? linkedBefore.get(plan.linkTarget.id) : null, before, after);
      }
      for (String id : expectSkipped) {
        assertEquals(WorkerJoinStatus.PENDING,
            db.template.findById(id, WorkerJoinRequest.class).getStatus(),
            "a skipped request stays PENDING");
      }
    }

    /**
     * While planning a batch: an approval changes what the next one in the same batch sees. Each
     * account has one open request, so the only cross-effect is two accounts' link requests for the
     * same employee, where the second finds it linked elsewhere.
     */
    private void previewApproval(Approval plan, Req r) {
      if (plan.linkTarget != null && plan.linkTarget.userId == null) {
        plan.linkTarget.userId = r.userId;
      }
    }

    void bulkReject(String shopId, List<String> labels, String reason) {
      List<String> ids = labels.stream().map(this::requestId).toList();
      List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
      String clean = cleanReason(reason);
      Refusal refusal = clean != null && clean.length() > WorkerJoinService.MAX_REASON_LENGTH
          ? Refusal.validation(WorkerJoinService.REASON_TOO_LONG) : null;
      Map<String, Req> byId = new LinkedHashMap<>();
      requests.values().forEach(r -> byId.put(r.id, r));
      List<Req> expectRejected = new ArrayList<>();
      Map<String, String> expectFailed = new LinkedHashMap<>();
      for (String id : unique) {
        Req r = byId.get(id);
        if (r == null || !r.shopId.equals(shopId)) {
          expectFailed.put(id, Refusal.notFound("Join request", id).message());
        } else if (r.status != WorkerJoinStatus.PENDING) {
          expectFailed.put(id, ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage());
        } else {
          expectRejected.add(r);
        }
      }
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      BulkJoinResult result =
          call("bulkReject", () -> db.service.bulkReject(APPROVER, shopId, ids, reason),
              refusal);
      if (result == null) {
        return;
      }
      Instant after = Instant.now();
      Map<String, String> failed = new LinkedHashMap<>();
      result.getFailed().forEach(f -> failed.put(f.getId(), f.getMessage()));
      assertEquals(expectRejected.size(), result.getApproved(), "rejected count");
      assertEquals(List.of(), result.getSkipped());
      assertEquals(expectFailed, failed);
      for (Req r : expectRejected) {
        r.status = WorkerJoinStatus.REJECTED;
        r.outcome = JoinOutcome.REJECTED;
        r.rejectionReason = clean;
        r.decidedByUserId = APPROVER;
        assertDecidedBetween(r, before, after);
      }
    }

    // ---- the worker's profile (Property 5) -----------------------------------------------------

    void editProfile(String userId, String name, String phone) {
      Acct a = accounts.get(userId);
      // mirrors WorkerAccountService.updateProfile (worker-app DD20): the phone is required and
      // checked first; a blank name means "unchanged", any other name is checked
      boolean keepName = name == null || name.isBlank();
      String cleanName = keepName ? a.name : name.strip();
      Optional<String> cleanPhone = PhoneNumbers.normalise(phone);
      Refusal refusal = null;
      if (cleanPhone.isEmpty()) {
        refusal = Refusal.validation(
            "Phone must be a valid Indian number (10 digits or +91 format)");
      } else if (!keepName && (cleanName.length() < 2 || cleanName.length() > 100)) {
        refusal = Refusal.validation("Name must be between 2 and 100 characters");
      }
      Object answer = call("editProfile",
          () -> db.workerAccounts.updateProfile(userId, name, phone), refusal);
      if (answer == null) {
        return;
      }
      a.name = cleanName;
      a.phone = cleanPhone.orElseThrow();
    }

    void togglePhoto(String userId) {
      Acct a = accounts.get(userId);
      if (a.photo) {
        db.photos.deleteById(userId);
      } else {
        db.template.insert(new UserPhoto(userId, new byte[] {1}, "image/jpeg", Instant.now()));
      }
      a.photo = !a.photo;
    }

    // ---- invariants ----------------------------------------------------------------------------

    /** Every collection the feature writes, row for row against the model. */
    void assertDatabaseAgrees() {
      // requests
      List<WorkerJoinRequest> rows = db.template.findAll(WorkerJoinRequest.class);
      assertEquals(requests.size(), rows.size(), "request rows " + steps);
      Map<String, WorkerJoinRequest> byId = new LinkedHashMap<>();
      rows.forEach(r -> byId.put(r.getId(), r));
      for (Req r : requests.values()) {
        WorkerJoinRequest row = byId.get(r.id);
        assertNotNull(row, r.label + " exists");
        String at = r.label + " (" + r.id + ")";
        assertEquals(r.shopId, row.getShopId(), at);
        assertEquals(r.userId, row.getUserId(), at);
        assertEquals(r.status, row.getStatus(), at + " status");
        assertFalse(row.getStatus() == WorkerJoinStatus.APPROVING,
            at + " is never left APPROVING");
        assertEquals(r.snapshot, row.getSnapshot(), at + " snapshot");
        assertEquals(r.requestedAt, row.getRequestedAt(), at + " requestedAt");
        assertEquals(r.outcome, row.getOutcome(), at + " outcome");
        assertEquals(r.employeeId, row.getEmployeeId(), at + " employeeId");
        assertEquals(r.decidedByUserId, row.getDecidedByUserId(), at + " decidedBy");
        assertEquals(r.rejectionReason, row.getRejectionReason(), at + " reason");
        if (r.status == WorkerJoinStatus.PENDING) {
          assertNull(row.getDecidedAt(), at + " undecided");
          assertNull(row.getExpiresAt(), at + " an open request never expires");
        } else {
          assertNotNull(row.getDecidedAt(), at + " decided");
          assertEquals(row.getDecidedAt().plus(WorkerJoinService.RETENTION), row.getExpiresAt(),
              at + " kept 90 days after the decision");
        }
      }
      // accounts
      for (Acct a : accounts.values()) {
        UserAccount row = db.template.findById(a.userId, UserAccount.class);
        assertNotNull(row);
        assertEquals(a.shopId, row.getShopId(), a.userId + " shopId");
        assertEquals(a.name, row.getName(), a.userId + " name");
        assertEquals(a.phone, row.getPhone(), a.userId + " phone");
        assertEquals(a.role, row.getRole(), a.userId + " role");
      }
      // employees
      List<Employee> staff = db.template.findAll(Employee.class);
      assertEquals(employees.size(), staff.size(), "employee rows");
      Map<String, Employee> staffById = new LinkedHashMap<>();
      staff.forEach(e -> staffById.put(e.getId(), e));
      for (Emp e : employees.values()) {
        Employee row = staffById.get(e.id);
        assertNotNull(row, e.label);
        assertEquals(e.shopId, row.getShopId(), e.label);
        assertEquals(e.code, row.getCode(), e.label);
        assertEquals(e.name, row.getName(), e.label);
        assertEquals(e.phone, row.getPhone(), e.label);
        assertEquals(e.email, row.getEmail(), e.label);
        assertEquals(e.status, row.getStatus(), e.label);
        assertEquals(e.userId, row.getUserId(), e.label + " userId");
      }
      // memberships: active WORKER rows as the model, staff rows as seeded
      Set<String> workers = new TreeSet<>();
      for (UserShopMembership m : db.template.findAll(UserShopMembership.class)) {
        if (m.getRole() == UserRole.WORKER) {
          assertTrue(m.isActive(), "a WORKER membership written here is active");
          assertEquals(UserShopMembershipService.RELATIONSHIP_INVITED, m.getRelationship());
          assertTrue(workers.add(m.getUserId() + "|" + m.getShopId()),
              "one WORKER membership per (account, shop)");
        }
      }
      assertEquals(workerMemberships, workers, "WORKER memberships");
      List<Document> staffRows = raw("user_shop_memberships").stream()
          .filter(d -> !UserRole.WORKER.name().equals(d.getString("role"))).toList();
      assertEquals(staffMemberships, staffRows, "staff memberships are never touched");
    }

    /** R3.5: one open request per account. */
    void assertOneOpenRequestPerAccount() {
      Map<String, Integer> openPerAccount = new TreeMap<>();
      for (WorkerJoinRequest row : db.template.findAll(WorkerJoinRequest.class)) {
        if (WorkerJoinService.OPEN.contains(row.getStatus())) {
          openPerAccount.merge(row.getUserId(), 1, Integer::sum);
        }
      }
      openPerAccount.forEach((user, n) ->
          assertTrue(n <= 1, user + " has " + n + " open requests after " + steps));
      // and the database rule itself: a second open row for an account cannot be inserted
      for (Acct a : accounts.values()) {
        if (openPerAccount.getOrDefault(a.userId, 0) == 1) {
          WorkerJoinRequest second = new WorkerJoinRequest();
          second.setShopId(SHOP_2);
          second.setUserId(a.userId);
          second.setStatus(WorkerJoinStatus.PENDING);
          second.setRequestedAt(Instant.now());
          try {
            db.template.insert(second);
            db.template.remove(second);
            fail("user_open_unique let a second open request through for " + a.userId);
          } catch (DuplicateKeyException expected) {
            // the index holds
          }
        }
      }
    }

    /** Property 5: the stored snapshot is what the model copied at filing, whatever came after. */
    void assertSnapshotsFrozen() {
      for (Req r : requests.values()) {
        WorkerJoinRequest row = db.template.findById(r.id, WorkerJoinRequest.class);
        assertEquals(r.snapshot, row.getSnapshot(), r.label + " snapshot frozen at filing");
        assertEquals(r.requestedAt, row.getRequestedAt(), r.label + " requestedAt frozen");
      }
    }

    /** Property 6: nothing in this feature writes a pairing or a punch. */
    void assertPairingsAndPunchesUntouched() {
      frozen.forEach((collection, expected) ->
          assertEquals(expected, raw(collection), collection + " byte-identical after " + steps));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Calls the service and checks the answer against the prediction: a predicted refusal must be
     * thrown with that code and text (and nothing returned); without one the call must succeed.
     */
    private <T> T call(String what, Supplier<T> call, Refusal expected) {
      Statistics.label("answers").collect(what + ": "
          + (expected == null ? "ok" : expected.code().name()));
      try {
        T answer = call.get();
        if (expected != null) {
          fail("expected " + expected + " but the call succeeded with " + answer + "; steps "
              + steps);
        }
        return answer;
      } catch (BaseException e) {
        if (expected == null) {
          fail("expected success, got " + e.getErrorCode() + ": " + e.getMessage() + "; steps "
              + steps, e);
        }
        assertEquals(expected.code(), e.getErrorCode(), "refusal code: " + e.getMessage());
        assertEquals(expected.message(), e.getMessage(), "refusal text");
        steps.add("refused " + what + ": " + expected.code().name());
        return null;
      }
    }

    private void assertDecidedBetween(Req r, Instant before, Instant after) {
      WorkerJoinRequest row = db.template.findById(r.id, WorkerJoinRequest.class);
      Instant decidedAt = row.getDecidedAt();
      assertNotNull(decidedAt, r.label + " decidedAt");
      assertFalse(decidedAt.isBefore(before) || decidedAt.isAfter(after),
          r.label + " decidedAt " + decidedAt + " in [" + before + ", " + after + "]");
    }

    private Document rawEmployee(String id) {
      return db.template.getCollection("hr_employees").find(new Document("_id", new ObjectId(id)))
          .first();
    }

    private List<Document> raw(String collection) {
      List<Document> docs = new ArrayList<>();
      db.template.getCollection(collection).find().sort(new Document("_id", 1)).into(docs);
      return docs;
    }

    private static Set<String> changedKeys(Document before, Document after) {
      Set<String> keys = new TreeSet<>(before.keySet());
      keys.addAll(after.keySet());
      keys.removeIf(k -> Objects.equals(before.get(k), after.get(k)));
      return keys;
    }

    /** R4.3 as the model reads it: e-mail equal ignoring case, or phone equal after normalising. */
    private static boolean possibleMatch(Req r, List<Emp> candidates) {
      String email = r.snapshot.getEmail();
      Optional<String> phone = PhoneNumbers.normalise(r.snapshot.getPhone());
      for (Emp e : candidates) {
        if (e.status != EmployeeStatus.ACTIVE && e.status != EmployeeStatus.EXITED) {
          continue;
        }
        boolean byEmail = email != null && e.email != null
            && email.trim().equalsIgnoreCase(e.email.trim());
        boolean byPhone = phone.isPresent() && PhoneNumbers.normalise(e.phone).equals(phone);
        if (byEmail || byPhone) {
          return true;
        }
      }
      return false;
    }

    /** R4.7: {@code trim(s)}, or none for a blank reason; the length rule is the caller's. */
    private static String cleanReason(String reason) {
      if (reason == null) {
        return null;
      }
      String t = reason.trim();
      return t.isEmpty() ? null : t;
    }
  }
}
