// Feature: worker-app, Property 29: Counter conservation
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.BaseException;
import com.inventory.common.tx.MongoTx;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.rest.dto.response.BulkJoinResult;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.WorkerJoinEvent;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.Tuple;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Property 29 of the worker-app design: for any sequence of create, approve (either mode), reject
 * and withdraw operations recorded against an in-memory meter, {@code
 * inventory_hr_worker_join_requests_total{event=created}} equals {@code approved_created +
 * approved_linked + rejected + withdrawn} plus the number of requests still {@code PENDING}, for
 * each {@code shop} label.
 *
 * <p>The sequence drives the real {@link WorkerJoinService} (so the call sites, not only {@link
 * HrWorkerCounters}, are what is checked) over an in-memory picture of {@code worker_join_requests}
 * that interprets the service's own queries and updates: the partial unique index on open requests,
 * the conditional {@code findAndModify} / {@code updateFirst} by {@code _id}, {@code shopId},
 * {@code userId} and {@code status}, and a transaction that puts the rows and the accounts back
 * when the approval throws. Operations are drawn so that many are refused (a request that does not
 * exist yet, is already decided, a staff account filing a join request, a second open request, a
 * rate limit, an employee of another shop): a refusal
 * must count nothing and a success exactly one, or the conservation law breaks.
 *
 * <p><b>Validates: Requirements 14.2</b>
 */
class WorkerJoinMetricsProperties {

  private static final List<String> SHOPS = List.of("shop-1", "shop-2");

  /** Three WORKER accounts without a shop and one staff account (CASHIER of shop-1). */
  private static final List<String> USERS = List.of("w1", "w2", "w3", "s1");

  private static final String APPROVER = "manager-1";
  private static final int EMPLOYEES_PER_SHOP = 2;

  /** Rejection reasons: none, blank, a real one and one over {@code MAX_REASON_LENGTH}. */
  private static final List<String> REASONS = List.of("", "   ", "Not hiring now",
      "x".repeat(WorkerJoinService.MAX_REASON_LENGTH + 1));

  enum OpType { CREATE, WITHDRAW, APPROVE, REJECT, BULK_APPROVE, BULK_REJECT }

  /**
   * One call on the service.
   *
   * @param user index into {@link #USERS}: the worker filing or withdrawing
   * @param shop index into {@link #SHOPS}: the shop filed for, or the approver's shop
   * @param ref which request the call names: {@code 0..7} the {@code ref}-th (cyclically) of the
   *     requests filed so far, so decisions mostly land on real rows; {@code 8} and {@code 9} the
   *     literal {@code req-8} / {@code req-9}, which often do not exist yet
   * @param employee an employee, numbered across both shops: {@code employee % EMPLOYEES_PER_SHOP}
   *     within its shop and {@code employee / EMPLOYEES_PER_SHOP} the shop. An approval in {@code
   *     LINK} mode names this employee, which is a refusal inside the transaction when it belongs
   *     to the other shop
   * @param linkMode approve with {@code LINK} (else {@code CREATE})
   * @param admitted what the rate limiter answers for this call
   * @param reason index into {@link #REASONS}, or {@code -1} for a null reason
   */
  record Op(OpType type, int user, int shop, int ref, int employee, boolean linkMode,
      boolean admitted, int reason) {

    String userId() {
      return USERS.get(user);
    }

    String shopId() {
      return SHOPS.get(shop);
    }

    String approveEmployeeId() {
      return linkMode
          ? employeeId(SHOPS.get(employee / EMPLOYEES_PER_SHOP), employee % EMPLOYEES_PER_SHOP)
          : null;
    }

    String reasonText() {
      return reason < 0 ? null : REASONS.get(reason);
    }

  }

  static String employeeId(String shopId, int index) {
    return shopId + "-e" + index;
  }

  @Provide
  Arbitrary<List<Op>> sequences() {
    // filing more often than deciding, so that decisions find rows to land on
    Arbitrary<OpType> types = Arbitraries.frequency(
        Tuple.of(5, OpType.CREATE), Tuple.of(2, OpType.WITHDRAW),
        Tuple.of(3, OpType.APPROVE), Tuple.of(2, OpType.REJECT), Tuple.of(1, OpType.BULK_APPROVE),
        Tuple.of(1, OpType.BULK_REJECT));
    Arbitrary<Op> ops = Combinators.combine(
            types,
            Arbitraries.integers().between(0, USERS.size() - 1),
            Arbitraries.integers().between(0, SHOPS.size() - 1),
            Arbitraries.integers().between(0, 9),
            Arbitraries.integers().between(0, SHOPS.size() * EMPLOYEES_PER_SHOP - 1),
            Arbitraries.of(true, false),
            // refused one call in five; a refusal creates and counts nothing (Property 28)
            Arbitraries.frequency(Tuple.of(4, true), Tuple.of(1, false)),
            Arbitraries.integers().between(-1, REASONS.size() - 1))
        .as(Op::new);
    return ops.list().ofMaxSize(40).withSizeDistribution(RandomDistribution.uniform());
  }

  // ---- properties ----------------------------------------------------------------------------

  @Property(tries = 300)
  void createdEqualsEveryDecisionPlusStillPending(@ForAll("sequences") List<Op> ops) {
    World w = new World();
    w.run(ops);

    for (String shop : SHOPS) {
      double created = w.count(shop, WorkerJoinEvent.CREATED);
      double decided = w.count(shop, WorkerJoinEvent.APPROVED_CREATED)
          + w.count(shop, WorkerJoinEvent.APPROVED_LINKED)
          + w.count(shop, WorkerJoinEvent.REJECTED)
          + w.count(shop, WorkerJoinEvent.WITHDRAWN);
      long pending = w.requests(shop).stream()
          .filter(r -> r.getStatus() == WorkerJoinStatus.PENDING).count();
      assertEquals(created, decided + pending,
          "created = approved_created + approved_linked + rejected + withdrawn + pending for "
              + shop + " after " + ops);
    }
  }

  @Property(tries = 300)
  void everyEventCounterIsTheNumberOfRequestsInThatOutcome(@ForAll("sequences") List<Op> ops) {
    World w = new World();
    w.run(ops);

    for (String shop : SHOPS) {
      List<WorkerJoinRequest> rs = w.requests(shop);
      String at = shop + " after " + ops;
      assertEquals(rs.size(), w.count(shop, WorkerJoinEvent.CREATED),
          "one 'created' per inserted request " + at);
      assertEquals(outcomes(rs, JoinOutcome.APPROVED_CREATED),
          w.count(shop, WorkerJoinEvent.APPROVED_CREATED), "approved_created " + at);
      assertEquals(outcomes(rs, JoinOutcome.APPROVED_LINKED),
          w.count(shop, WorkerJoinEvent.APPROVED_LINKED), "approved_linked " + at);
      assertEquals(outcomes(rs, JoinOutcome.REJECTED),
          w.count(shop, WorkerJoinEvent.REJECTED), "rejected " + at);
      assertEquals(outcomes(rs, JoinOutcome.WITHDRAWN),
          w.count(shop, WorkerJoinEvent.WITHDRAWN), "withdrawn " + at);
      assertEquals(0, rs.stream().filter(r -> r.getStatus() == WorkerJoinStatus.APPROVING)
          .count(), "no request is left APPROVING by a finished approval " + at);
    }
    for (Meter meter : w.registry.getMeters()) {
      assertEquals(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, meter.getId().getName(),
          "no other meter moves");
      assertTrue(SHOPS.contains(meter.getId().getTag(HrMetricsConstants.TAG_SHOP)),
          "every series carries a known shop: " + meter.getId());
      assertEquals(null, meter.getId().getTag("kind"), "no kind tag (D11): " + meter.getId());
    }
  }

  private static long outcomes(List<WorkerJoinRequest> rs, JoinOutcome outcome) {
    return rs.stream().filter(r -> r.getOutcome() == outcome).count();
  }

  // ---- the world the service runs in ---------------------------------------------------------

  /**
   * The collection, the accounts and the meter, with the service wired over them. Every read hands
   * out a copy, as the driver would, so the service's own mutations never reach the store except
   * through its queries.
   */
  static final class World {
    final Map<String, WorkerJoinRequest> requests = new LinkedHashMap<>();
    final Map<String, AccountView> accounts = new LinkedHashMap<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final WorkerJoinService service = new WorkerJoinService();
    int nextRequest = 1;
    int nextEmployee = 1;
    boolean admitted = true;

    @SuppressWarnings("unchecked")
    World() {
      for (String user : USERS) {
        boolean worker = user.startsWith("w");
        accounts.put(user, new AccountView(user, "Name " + user, user + "@example.com",
            "98765432" + user.charAt(1) + "0", worker ? "WORKER" : "CASHIER",
            worker ? null : SHOPS.get(0), worker));
      }

      WorkerJoinRequestRepository requestRepository = mock(WorkerJoinRequestRepository.class);
      when(requestRepository.insert(any(WorkerJoinRequest.class)))
          .thenAnswer(inv -> insert(inv.getArgument(0)));
      when(requestRepository.findFirstByUserIdAndStatusIn(anyString(), anyCollection()))
          .thenAnswer(inv -> {
            String userId = inv.getArgument(0);
            Collection<WorkerJoinStatus> statuses = inv.getArgument(1);
            return requests.values().stream()
                .filter(r -> userId.equals(r.getUserId()) && statuses.contains(r.getStatus()))
                .findFirst().map(World::copy);
          });
      when(requestRepository.findByShopIdAndId(anyString(), anyString())).thenAnswer(inv ->
          Optional.ofNullable(requests.get((String) inv.getArgument(1)))
              .filter(r -> inv.getArgument(0).equals(r.getShopId()))
              .map(World::copy));
      when(requestRepository.findById(anyString())).thenAnswer(inv ->
          Optional.ofNullable(requests.get((String) inv.getArgument(0))).map(World::copy));

      MongoTemplate mongoTemplate = mock(MongoTemplate.class);
      when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
          any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class)))
          .thenAnswer(inv -> findAndModify(inv.getArgument(0), inv.getArgument(1)));
      when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
          eq(WorkerJoinRequest.class)))
          .thenAnswer(inv -> updateFirst(inv.getArgument(0), inv.getArgument(1)));

      // a transaction: the work runs at once; when it throws, rows and accounts are as before
      MongoTx mongoTx = mock(MongoTx.class);
      when(mongoTx.run(anyString(), anyString(), any(Supplier.class))).thenAnswer(inv -> {
        Map<String, WorkerJoinRequest> rows = snapshot();
        Map<String, AccountView> views = new LinkedHashMap<>(accounts);
        try {
          return ((Supplier<Object>) inv.getArgument(2)).get();
        } catch (RuntimeException e) {
          requests.clear();
          requests.putAll(rows);
          accounts.clear();
          accounts.putAll(views);
          throw e;
        }
      });

      WorkerAccounts workerAccounts = mock(WorkerAccounts.class);
      when(workerAccounts.account(anyString()))
          .thenAnswer(inv -> Optional.ofNullable(accounts.get((String) inv.getArgument(0))));
      when(workerAccounts.hasPhoto(anyString())).thenReturn(false);
      when(workerAccounts.claimShop(anyString(), anyString()))
          .thenAnswer(inv -> claimShop(inv.getArgument(0), inv.getArgument(1)));

      EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
      when(employeeRepository.findByUserIdAndStatus(anyString(), any())).thenReturn(List.of());
      when(employeeRepository.findMatchCandidates(anyString(), anyCollection()))
          .thenReturn(List.of());
      when(employeeRepository.findByShopIdAndId(anyString(), anyString())).thenAnswer(inv -> {
        String shopId = inv.getArgument(0);
        String id = inv.getArgument(1);
        for (int i = 0; i < EMPLOYEES_PER_SHOP; i++) {
          if (employeeId(shopId, i).equals(id)) {
            return Optional.of(employee(shopId, id));
          }
        }
        return Optional.empty();
      });

      EmployeeService employeeService = mock(EmployeeService.class);
      when(employeeService.createFromJoinRequest(anyString(), anyString(), any()))
          .thenAnswer(inv -> employee(inv.getArgument(0), "emp-new-" + nextEmployee++));
      when(employeeService.linkFromJoinRequest(anyString(), anyString(), any(), anyString()))
          .thenAnswer(inv -> inv.getArgument(2));

      AppRateLimiter rateLimiter = mock(AppRateLimiter.class);
      when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class)))
          .thenAnswer(inv -> admitted);

      ShopNamePort shopNamePort = mock(ShopNamePort.class);
      when(shopNamePort.getShopName(anyString())).thenAnswer(inv -> "Shop " + inv.getArgument(0));

      ReflectionTestUtils.setField(service, "requestRepository", requestRepository);
      ReflectionTestUtils.setField(service, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
      ReflectionTestUtils.setField(service, "mongoTx", mongoTx);
      ReflectionTestUtils.setField(service, "employeeService", employeeService);
      ReflectionTestUtils.setField(service, "workerAccounts", workerAccounts);
      ReflectionTestUtils.setField(service, "shopNamePort", shopNamePort);
      ReflectionTestUtils.setField(service, "guard", mock(HrPermissionGuard.class));
      ReflectionTestUtils.setField(service, "auditService", mock(HrAuditService.class));
      ReflectionTestUtils.setField(service, "notificationService",
          mock(HrNotificationService.class));
      ReflectionTestUtils.setField(service, "rateLimiter", rateLimiter);
      ReflectionTestUtils.setField(service, "counters",
          new HrWorkerCounters(new MetricsWrapper(registry)));
    }

    /**
     * Runs the sequence; a refused call ({@link BaseException} and its subclasses, which is every
     * refusal the service makes) counts nothing. After each call the meter moved by exactly the
     * number of events the call reports: one for a success, the number approved or rejected for a
     * bulk action.
     */
    void run(List<Op> ops) {
      int step = 0;
      for (Op op : ops) {
        admitted = op.admitted();
        double before = total();
        int events;
        try {
          events = switch (op.type()) {
            case CREATE -> {
              service.create(op.userId(), op.shopId());
              yield 1;
            }
            case WITHDRAW -> {
              service.withdraw(op.userId(), requestId(op.ref()));
              yield 1;
            }
            case APPROVE -> {
              service.approve(APPROVER, op.shopId(), requestId(op.ref()),
                  op.linkMode() ? JoinApproveMode.LINK : JoinApproveMode.CREATE,
                  op.approveEmployeeId());
              yield 1;
            }
            case REJECT -> {
              service.reject(APPROVER, op.shopId(), requestId(op.ref()), op.reasonText());
              yield 1;
            }
            case BULK_APPROVE -> {
              BulkJoinResult result = service.bulkApprove(APPROVER, op.shopId(),
                  bulkIds(op.ref()));
              yield result.getApproved();
            }
            case BULK_REJECT -> {
              BulkJoinResult result = service.bulkReject(APPROVER, op.shopId(), bulkIds(op.ref()),
                  op.reasonText());
              yield result.getApproved();
            }
          };
        } catch (BaseException refused) {
          events = 0;
        }
        assertEquals(before + events, total(),
            "step " + step + " " + op + " moved the meter by exactly its events; sequence " + ops);
        step++;
      }
    }

    /** See {@link Op#ref()}. */
    String requestId(int ref) {
      int filed = nextRequest - 1;
      return ref < 8 && filed > 0 ? "req-" + (1 + ref % filed) : "req-" + ref;
    }

    /** Three references for a bulk action; duplicates and missing rows are for the service. */
    List<String> bulkIds(int ref) {
      return List.of(requestId(ref), requestId((ref + 1) % 10), requestId((ref + 3) % 10));
    }

    List<WorkerJoinRequest> requests(String shopId) {
      return requests.values().stream().filter(r -> shopId.equals(r.getShopId())).toList();
    }

    double count(String shop, String event) {
      Counter c = registry.find(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL)
          .tag("module", HrMetricsConstants.MODULE)
          .tag(HrMetricsConstants.TAG_SHOP, shop)
          .tag(HrMetricsConstants.TAG_EVENT, event)
          .counter();
      return c != null ? c.count() : 0.0;
    }

    /** Every increment on every series of the join-request meter. */
    double total() {
      return registry.find(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL).counters().stream()
          .mapToDouble(Counter::count).sum();
    }

    // ---- the collection ----------------------------------------------------------------------

    /** {@code insert} under the partial unique index {@code user_open_unique}. */
    private WorkerJoinRequest insert(WorkerJoinRequest r) {
      boolean open = requests.values().stream().anyMatch(x -> x.getUserId().equals(r.getUserId())
          && WorkerJoinService.OPEN.contains(x.getStatus()));
      if (open) {
        throw new DuplicateKeyException("user_open_unique");
      }
      r.setId("req-" + nextRequest++);
      requests.put(r.getId(), copy(r));
      return r;
    }

    private WorkerJoinRequest findAndModify(Query query, Update update) {
      for (WorkerJoinRequest r : requests.values()) {
        if (matches(r, query)) {
          apply(r, update);
          return copy(r);
        }
      }
      return null;
    }

    private UpdateResult updateFirst(Query query, Update update) {
      WorkerJoinRequest modified = findAndModify(query, update);
      return modified == null ? UpdateResult.acknowledged(0, 0L, null)
          : UpdateResult.acknowledged(1, 1L, null);
    }

    /** Equality on the four fields the service filters by; anything else is a modelling gap. */
    private static boolean matches(WorkerJoinRequest r, Query query) {
      for (Map.Entry<String, Object> e : query.getQueryObject().entrySet()) {
        if (e.getValue() instanceof Document) {
          throw new IllegalStateException("unmodelled operator on " + e.getKey() + ": " + e);
        }
        Object actual = switch (e.getKey()) {
          case "_id" -> r.getId();
          case "shopId" -> r.getShopId();
          case "userId" -> r.getUserId();
          case "status" -> r.getStatus();
          default -> throw new IllegalStateException("unmodelled query field " + e.getKey());
        };
        if (!Objects.equals(String.valueOf(actual), String.valueOf(e.getValue()))) {
          return false;
        }
      }
      return true;
    }

    private static void apply(WorkerJoinRequest r, Update update) {
      Document u = update.getUpdateObject();
      for (String operator : u.keySet()) {
        Document fields = (Document) u.get(operator);
        switch (operator) {
          case "$set" -> fields.forEach((field, value) -> set(r, field, value));
          case "$unset" -> fields.keySet().forEach(field -> set(r, field, null));
          default -> throw new IllegalStateException("unmodelled update operator " + operator);
        }
      }
    }

    private static void set(WorkerJoinRequest r, String field, Object value) {
      switch (field) {
        case "status" -> r.setStatus((WorkerJoinStatus) value);
        case "outcome" -> r.setOutcome((JoinOutcome) value);
        case "employeeId" -> r.setEmployeeId((String) value);
        case "decidedAt" -> r.setDecidedAt((Instant) value);
        case "decidedByUserId" -> r.setDecidedByUserId((String) value);
        case "rejectionReason" -> r.setRejectionReason((String) value);
        case "expiresAt" -> r.setExpiresAt((Instant) value);
        case "updatedAt" -> r.setUpdatedAt((Instant) value);
        default -> throw new IllegalStateException("unmodelled update field " + field);
      }
    }

    private Map<String, WorkerJoinRequest> snapshot() {
      Map<String, WorkerJoinRequest> rows = new LinkedHashMap<>();
      requests.forEach((id, r) -> rows.put(id, copy(r)));
      return rows;
    }

    static WorkerJoinRequest copy(WorkerJoinRequest r) {
      WorkerJoinRequest c = new WorkerJoinRequest();
      c.setId(r.getId());
      c.setShopId(r.getShopId());
      c.setUserId(r.getUserId());
      c.setStatus(r.getStatus());
      c.setSnapshot(r.getSnapshot());
      c.setRequestedAt(r.getRequestedAt());
      c.setDecidedAt(r.getDecidedAt());
      c.setDecidedByUserId(r.getDecidedByUserId());
      c.setOutcome(r.getOutcome());
      c.setEmployeeId(r.getEmployeeId());
      c.setRejectionReason(r.getRejectionReason());
      c.setExpiresAt(r.getExpiresAt());
      c.setUpdatedAt(r.getUpdatedAt());
      return c;
    }

    // ---- accounts and employees --------------------------------------------------------------

    /** DD1: a WORKER account's one shop; claimed when none, kept when the same, refused else. */
    private boolean claimShop(String shopId, String userId) {
      AccountView a = accounts.get(userId);
      if (a.shopId() == null) {
        accounts.put(userId, new AccountView(a.userId(), a.name(), a.email(), a.phone(), a.role(),
            shopId, a.worker()));
        return true;
      }
      return shopId.equals(a.shopId());
    }

    private static Employee employee(String shopId, String id) {
      Employee e = new Employee();
      e.setId(id);
      e.setShopId(shopId);
      e.setCode("EMP-" + id);
      e.setName("Employee " + id);
      e.setStatus(EmployeeStatus.ACTIVE);
      return e;
    }
  }
}
