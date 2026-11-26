// Feature: worker-app, Property 12: Membership ends with the exit date
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.service.WorkerAccountService;
import com.inventory.user.service.api.WorkerAccountsImpl;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Property 12 of the worker-app design: for any exit date {@code d}, current instant {@code now}
 * and employee linked to a WORKER account, after one job tick {@code membershipEndedAt} is set,
 * the membership is inactive and {@code UserAccount.shopId} is null iff {@code
 * d.atStartOfDay(Asia/Kolkata) <= now}; otherwise all three are untouched, and they become set at
 * the first tick after that instant. {@code Employee.userId} is unchanged in every case; a staff
 * account's membership is never ended (R5.2, R5.3, R13.4).
 *
 * <p>The code under test is the real chain {@link WorkerExitJob} → {@link
 * WorkerMembershipEnderImpl} → {@link WorkerAccountsImpl} → {@link WorkerAccountService#releaseShop}
 * over an in-memory picture of the three collections ({@code hr_employees}, {@code users}, {@code
 * user_shop_memberships}). The {@link MongoTemplate} is a fake that interprets exactly the query
 * and update shapes those classes issue ({@code =}, {@code null}, {@code $lte}, {@code $type},
 * {@code $set}, {@code $unset}) and throws on anything else, so a write to any field the rule does
 * not name fails the property instead of passing unseen. The clock is fixed per tick and shared by
 * the job and the ender; the user module's {@code endedAt} uses the wall clock, so only its
 * presence is compared.
 *
 * <p>The population is any mix of ACTIVE and EXITED employees in two shops, exit dates from 400
 * days before to 30 days after the base day (with the day before, the day and the day after the
 * base day often), linked to one of four accounts (WORKER or staff, holding the same shop, another
 * shop or none, with or without an active membership), to a deleted account, or to no account;
 * some exits already ended. The job runs at one to five increasing instants, often exactly at, one
 * second before or one second after an IST midnight.
 *
 * <p><b>Validates: Requirements 5.2, 5.3</b>
 */
class WorkerExitJobProperties {

  private static final ZoneId IST = HrDateUtils.ZONE;

  /** 2025-04-09 14:42 IST, the day the exit dates are measured from. */
  private static final Instant BASE = Instant.parse("2025-04-09T09:12:00Z");
  private static final LocalDate BASE_DAY = LocalDate.ofInstant(BASE, IST);

  private static final List<String> SHOPS = List.of("shop-a", "shop-b");
  private static final List<UserRole> STAFF =
      List.of(UserRole.OWNER, UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER);

  /** Accounts {@code user-0..user-3}; a worker referring to index {@value} has no account at all. */
  private static final int POOL = 4;

  // ---- scenario ------------------------------------------------------------------------------

  /**
   * One account of the pool.
   *
   * @param role the role, or null when the account does not exist (the employee's login was
   *     deleted)
   * @param shop index into {@link #SHOPS} of {@code UserAccount.shopId}, or null for none
   * @param memberships shops (indexes) the account has an active membership in, with its role
   */
  record Account(UserRole role, Integer shop, Set<Integer> memberships) {}

  /**
   * One employee.
   *
   * @param status ACTIVE or EXITED
   * @param exitOffsetDays the exit date as days from {@link #BASE_DAY}; always set for EXITED
   * @param staleDate for an ACTIVE employee, whether an (old) exit date is still on the row
   * @param bySelf the exit was done by the worker ("self", R5.3) rather than a manager
   * @param user index into the account pool, {@link #POOL} for a deleted account, null for an
   *     employee that was never linked
   * @param alreadyEnded {@code membershipEndedAt} is already written
   * @param shop index into {@link #SHOPS}
   */
  record Shape(EmployeeStatus status, int exitOffsetDays, boolean staleDate, boolean bySelf,
      Integer user, boolean alreadyEnded, int shop) {

    String exitDate() {
      boolean dated = status == EmployeeStatus.EXITED || staleDate;
      return dated ? HrDateUtils.format(BASE_DAY.plusDays(exitOffsetDays)) : null;
    }

    String userId() {
      return user == null ? null : "user-" + user;
    }
  }

  record Scenario(List<Account> accounts, List<Shape> employees, List<Instant> runs) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<UserRole> roles = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.just(UserRole.WORKER)),
        Tuple.of(1, Arbitraries.of(STAFF)));
    Arbitrary<Account> account = Combinators.combine(
            roles.injectNull(0.15),
            Arbitraries.of(0, 1).injectNull(0.3),
            Arbitraries.subsetOf(0, 1))
        .as(Account::new);

    // the day before, the day and the day after the base day one try in three, else any day in
    // the window; no edge cases, or every try of the wide product lands on the same few values
    Arbitrary<Integer> exitOffsets = Arbitraries.frequencyOf(
        Tuple.of(1, Arbitraries.of(-1, 0, 1)),
        Tuple.of(2, Arbitraries.integers().between(-400, 30).withoutEdgeCases()));
    Arbitrary<Shape> shape = Combinators.combine(
            Arbitraries.frequency(
                Tuple.of(3, EmployeeStatus.EXITED), Tuple.of(1, EmployeeStatus.ACTIVE)),
            exitOffsets,
            Arbitraries.of(true, false),
            Arbitraries.of(true, false),
            Arbitraries.integers().between(0, POOL).injectNull(0.2),
            Arbitraries.frequency(Tuple.of(9, false), Tuple.of(1, true)),
            Arbitraries.of(0, 1))
        .as(Shape::new);

    return Combinators.combine(
            account.list().ofSize(POOL),
            shape.list().ofMaxSize(10),
            runs())
        .as(Scenario::new);
  }

  /** One to five distinct, increasing instants; half of them sit on an IST midnight ± 1 s. */
  private static Arbitrary<List<Instant>> runs() {
    Arbitrary<Instant> boundary = Arbitraries.integers().between(-2, 40).flatMap(k ->
        Arbitraries.of(-1L, 0L, 1L).map(delta ->
            BASE_DAY.plusDays(k).atStartOfDay(IST).toInstant().plusSeconds(delta)));
    Arbitrary<Instant> anywhere = Arbitraries.longs()
        .between(-2L * 86_400, 40L * 86_400)
        .withoutEdgeCases()
        .map(BASE::plusSeconds);
    return Arbitraries.frequencyOf(Tuple.of(1, boundary), Tuple.of(1, anywhere))
        .list().ofMinSize(1).ofMaxSize(5)
        .map(list -> list.stream().distinct().sorted().toList());
  }

  // ---- properties ----------------------------------------------------------------------------

  @Property(tries = 200)
  void exactlyTheDueExitsEndAtTheFirstTickAfterTheirMidnightAndNothingElseChanges(
      @ForAll("scenarios") Scenario s) {
    World world = new World(s, 0);
    WorldState expected = world.snapshot();

    for (Instant tick : s.runs()) {
      world.tick(tick);
      expected = expected.after(tick);
    }

    assertEquals(expected, world.snapshot(), s.toString());
  }

  /** The design sentence, checked field by field on the rows the rule names. */
  @Property(tries = 200)
  void aWorkerIsReleasedIffMidnightOfTheExitDateHasPassedAndStaffIsNeverTouched(
      @ForAll("scenarios") Scenario s) {
    World world = new World(s, 0);
    WorldState before = world.snapshot();

    for (Instant tick : s.runs()) {
      world.tick(tick);
    }
    WorldState after = world.snapshot();

    for (int i = 0; i < before.employees().size(); i++) {
      EmployeeState was = before.employees().get(i);
      EmployeeState now = after.employees().get(i);
      assertEquals(was.userId(), now.userId(), "userId is never cleared: " + was);
      assertEquals(was.status(), now.status(), was.toString());
      assertEquals(was.exitDate(), now.exitDate(), was.toString());

      boolean linkedExit = was.status() == EmployeeStatus.EXITED && was.userId() != null
          && was.exitDate() != null && was.membershipEndedAt() == null;
      if (!linkedExit) {
        assertEquals(was.membershipEndedAt(), now.membershipEndedAt(),
            "an unlinked, active or already ended row is never written: " + was);
        continue;
      }
      Instant midnight = LocalDate.parse(was.exitDate()).atStartOfDay(IST).toInstant();
      Optional<Instant> firstTickDue = s.runs().stream().filter(t -> !t.isBefore(midnight))
          .findFirst();
      assertEquals(firstTickDue.orElse(null), now.membershipEndedAt(),
          "ended at the first tick at or after the exit date's midnight, else untouched: " + was);

      AccountState accountWas = before.account(was.userId());
      AccountState accountNow = after.account(was.userId());
      Optional<MembershipState> membershipWas = before.activeMembership(was.userId(), was.shopId());
      Optional<MembershipState> membershipNow = after.membership(was.userId(), was.shopId());
      if (before.employeesLinkedTo(was.userId()) > 1) {
        // the design sentence is about one employee per account; an account linked to several
        // exited employees (left shop A, then shop B) is the whole-state model's to judge
        continue;
      }
      if (accountWas == null) {
        assertNull(accountNow, "a deleted login stays deleted: " + was);
      } else if (accountWas.role() != UserRole.WORKER) {
        assertEquals(accountWas, accountNow, "a staff account keeps its shop (R13.4): " + was);
        assertEquals(membershipWas, membershipNow,
            "a staff membership is never ended (R13.4): " + was);
      } else if (firstTickDue.isEmpty()) {
        assertEquals(accountWas, accountNow, "a future exit leaves the account alone: " + was);
        assertEquals(membershipWas, membershipNow, "and its membership: " + was);
      } else {
        boolean heldThisShop = was.shopId().equals(accountWas.shopId());
        assertEquals(heldThisShop ? null : accountWas.shopId(), accountNow.shopId(),
            "the account's shop is released iff it was this shop: " + was);
        membershipWas.ifPresent(m -> {
          assertTrue(membershipNow.isPresent(), "the membership row is kept: " + was);
          assertFalse(membershipNow.get().active(), "the WORKER membership is inactive: " + was);
          assertTrue(membershipNow.get().ended(), "and carries endedAt: " + was);
        });
      }
    }
  }

  @Property(tries = 200)
  void aSecondTickAtTheSameInstantIsANoOp(@ForAll("scenarios") Scenario s) {
    World world = new World(s, 0);

    for (Instant tick : s.runs()) {
      world.tick(tick);
      WorldState once = world.snapshot();

      assertEquals(0, world.tick(tick), "nothing is due any more at " + tick + ": " + s);
      assertEquals(once, world.snapshot(), "a repeated tick changes nothing: " + s);
    }
  }

  @Property(tries = 200)
  void theOrderInWhichDueEmployeesAreHandledDoesNotMatter(
      @ForAll("scenarios") Scenario s, @ForAll long orderA, @ForAll long orderB) {
    World a = new World(s, orderA);
    World b = new World(s, orderB);

    for (Instant tick : s.runs()) {
      a.tick(tick);
      b.tick(tick);
      assertEquals(a.snapshot(), b.snapshot(), "same tick, different order: " + s);
    }
  }

  // ---- state and model -----------------------------------------------------------------------

  record EmployeeState(String id, String shopId, String userId, EmployeeStatus status,
      String exitDate, Instant membershipEndedAt, String updatedByUserId, String name) {

    EmployeeState ended(Instant at) {
      return new EmployeeState(id, shopId, userId, status, exitDate, at, updatedByUserId, name);
    }
  }

  record AccountState(String userId, UserRole role, String shopId) {

    AccountState released() {
      return new AccountState(userId, role, null);
    }
  }

  /** {@code ended} stands for {@code endedAt != null}: the user module stamps it with the wall clock. */
  record MembershipState(String id, String userId, String shopId, UserRole role, boolean active,
      boolean ended) {

    MembershipState closed() {
      return new MembershipState(id, userId, shopId, role, false, true);
    }
  }

  /** The three collections as the rule sees them. */
  record WorldState(List<EmployeeState> employees, List<AccountState> accounts,
      List<MembershipState> memberships) {

    AccountState account(String userId) {
      return accounts.stream().filter(a -> a.userId().equals(userId)).findFirst().orElse(null);
    }

    long employeesLinkedTo(String userId) {
      return employees.stream().filter(e -> userId.equals(e.userId())).count();
    }

    Optional<MembershipState> activeMembership(String userId, String shopId) {
      return membership(userId, shopId).filter(MembershipState::active);
    }

    Optional<MembershipState> membership(String userId, String shopId) {
      return memberships.stream()
          .filter(m -> m.userId().equals(userId) && m.shopId().equals(shopId))
          .findFirst();
    }

    /**
     * R5.2 / R5.3 / R13.4 as a rule over the state: every EXITED, linked employee whose exit
     * date's midnight (Asia/Kolkata) is at or before {@code now} and whose ledger is open gets
     * {@code membershipEndedAt = now}; when its login is a WORKER account, the account's shop is
     * cleared if it is this shop and its active WORKER membership here is closed. Nothing else
     * moves.
     */
    WorldState after(Instant now) {
      LocalDate today = LocalDate.ofInstant(now, IST);
      Map<String, AccountState> accountsNow = new LinkedHashMap<>();
      accounts.forEach(a -> accountsNow.put(a.userId(), a));
      List<MembershipState> membershipsNow = new ArrayList<>(memberships);
      List<EmployeeState> employeesNow = new ArrayList<>();
      for (EmployeeState e : employees) {
        boolean due = e.status() == EmployeeStatus.EXITED
            && e.userId() != null
            && e.exitDate() != null
            && !LocalDate.parse(e.exitDate()).isAfter(today)
            && e.membershipEndedAt() == null;
        if (!due) {
          employeesNow.add(e);
          continue;
        }
        AccountState account = accountsNow.get(e.userId());
        if (account != null && account.role() == UserRole.WORKER) {
          if (e.shopId().equals(account.shopId())) {
            accountsNow.put(account.userId(), account.released());
          }
          membershipsNow.replaceAll(m ->
              m.userId().equals(e.userId()) && m.shopId().equals(e.shopId()) && m.active()
                  && m.role() == UserRole.WORKER ? m.closed() : m);
        }
        employeesNow.add(e.ended(now));
      }
      return new WorldState(employeesNow, List.copyOf(accountsNow.values()), membershipsNow);
    }
  }

  // ---- world: the real chain over an in-memory database --------------------------------------

  /**
   * {@link WorkerExitJob} → {@link WorkerMembershipEnderImpl} → {@link WorkerAccountsImpl} →
   * {@link WorkerAccountService}, all real, over maps that stand in for the three collections. The
   * fake {@link MongoTemplate} hands out copies on a read (a document in hand is detached, as with
   * Mongo) and applies conditional updates to the stored row; {@code order} shuffles what the due
   * read returns.
   */
  static final class World {

    private final Map<String, Employee> employees = new LinkedHashMap<>();
    private final Map<String, UserAccount> accounts = new LinkedHashMap<>();
    private final Map<String, UserShopMembership> memberships = new LinkedHashMap<>();
    private final Random order;
    private final WorkerExitJob job;
    private final WorkerMembershipEnderImpl ender;
    private int handed;

    World(Scenario s, long orderSeed) {
      this.order = new Random(orderSeed);
      for (int i = 0; i < s.accounts().size(); i++) {
        Account a = s.accounts().get(i);
        if (a.role() == null) {
          continue;
        }
        String userId = "user-" + i;
        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setName("Worker " + i);
        account.setEmail(userId + "@example.com");
        account.setRole(a.role());
        account.setShopId(a.shop() == null ? null : SHOPS.get(a.shop()));
        account.setActive(true);
        accounts.put(userId, account);
        for (int shop : a.memberships()) {
          UserShopMembership m = new UserShopMembership();
          m.setMembershipId(userId + ":" + SHOPS.get(shop));
          m.setUserId(userId);
          m.setShopId(SHOPS.get(shop));
          m.setRole(a.role());
          m.setRelationship(a.role() == UserRole.OWNER ? "OWNER" : "INVITED");
          m.setActive(true);
          m.setJoinedAt(BASE.minusSeconds(86_400L * 500));
          memberships.put(m.getMembershipId(), m);
        }
      }
      for (int i = 0; i < s.employees().size(); i++) {
        Shape w = s.employees().get(i);
        Employee e = new Employee();
        e.setId("emp-" + i);
        e.setShopId(SHOPS.get(w.shop()));
        e.setCode(String.format("EMP-%03d", i + 1));
        e.setName("Employee " + i);
        e.setUserId(w.userId());
        e.setJoinDate("2024-01-01");
        e.setStatus(w.status());
        e.setExitDate(w.exitDate());
        e.setMembershipEndedAt(w.alreadyEnded() ? BASE.minusSeconds(86_400L * 10) : null);
        e.setUpdatedByUserId(w.bySelf() ? "self" : "manager-1");
        e.setRevision(0L);
        employees.put(e.getId(), e);
      }

      MongoTemplate mongo = mock(MongoTemplate.class);
      when(mongo.find(any(Query.class), eq(Employee.class))).thenAnswer(inv -> {
        Document where = inv.<Query>getArgument(0).getQueryObject();
        List<Employee> hits = new ArrayList<>();
        for (Employee e : employees.values()) {
          if (matches(where, employeeFields(e))) {
            hits.add(copy(e));
          }
        }
        Collections.shuffle(hits, order);
        return hits;
      });
      when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Employee.class)))
          .thenAnswer(inv -> {
            Document where = inv.<Query>getArgument(0).getQueryObject();
            Document update = inv.<Update>getArgument(1).getUpdateObject();
            for (Employee e : employees.values()) {
              if (matches(where, employeeFields(e))) {
                applyToEmployee(e, update);
                return UpdateResult.acknowledged(1, 1L, null);
              }
            }
            return UpdateResult.acknowledged(0, 0L, null);
          });
      when(mongo.updateFirst(any(Query.class), any(Update.class), eq(UserAccount.class)))
          .thenAnswer(inv -> {
            Document where = inv.<Query>getArgument(0).getQueryObject();
            Document update = inv.<Update>getArgument(1).getUpdateObject();
            for (UserAccount a : accounts.values()) {
              if (matches(where, accountFields(a))) {
                applyToAccount(a, update);
                return UpdateResult.acknowledged(1, 1L, null);
              }
            }
            return UpdateResult.acknowledged(0, 0L, null);
          });

      UserAccountRepository userAccounts = mock(UserAccountRepository.class);
      when(userAccounts.findById(anyString()))
          .thenAnswer(inv -> Optional.ofNullable(accounts.get(inv.<String>getArgument(0))));

      UserShopMembershipRepository membershipRepository = mock(UserShopMembershipRepository.class);
      when(membershipRepository.findByUserIdAndShopIdAndActiveTrue(anyString(), anyString()))
          .thenAnswer(inv -> {
            String userId = inv.getArgument(0);
            String shopId = inv.getArgument(1);
            return memberships.values().stream()
                .filter(m -> m.isActive() && userId.equals(m.getUserId())
                    && shopId.equals(m.getShopId()))
                .findFirst()
                .map(World::copy);
          });
      when(membershipRepository.save(any(UserShopMembership.class))).thenAnswer(inv -> {
        UserShopMembership m = inv.getArgument(0);
        memberships.put(m.getMembershipId(), copy(m));
        return m;
      });

      WorkerJoinRequestRepository joinRequests = mock(WorkerJoinRequestRepository.class);
      when(joinRequests.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());

      WorkerAccountService accountService = new WorkerAccountService();
      ReflectionTestUtils.setField(accountService, "mongoTemplate", mongo);
      ReflectionTestUtils.setField(accountService, "userAccountRepository", userAccounts);
      ReflectionTestUtils.setField(accountService, "membershipRepository", membershipRepository);
      WorkerAccounts workerAccounts =
          new WorkerAccountsImpl(userAccounts, accountService, null, null, null);

      ender = new WorkerMembershipEnderImpl();
      ReflectionTestUtils.setField(ender, "mongoTemplate", mongo);
      ReflectionTestUtils.setField(ender, "workerAccounts", workerAccounts);

      WorkerMembershipEnder counting = employee -> {
        handed++;
        ender.onExit(employee);
      };
      job = new WorkerExitJob();
      ReflectionTestUtils.setField(job, "mongoTemplate", mongo);
      ReflectionTestUtils.setField(job, "joinRequestRepository", joinRequests);
      ReflectionTestUtils.setField(job, "membershipEnder", counting);
      ReflectionTestUtils.setField(job, "enabled", true);
    }

    /**
     * One scheduled tick at {@code now}: the job's {@code run()} with the clock pinned.
     *
     * @return how many employees the due read handed to the ender during this tick
     */
    int tick(Instant now) {
      Clock clock = Clock.fixed(now, ZoneOffset.UTC);
      job.setClock(clock);
      ender.setClock(clock);
      int before = handed;
      job.run();
      return handed - before;
    }

    WorldState snapshot() {
      List<EmployeeState> es = employees.values().stream().map(e -> new EmployeeState(e.getId(),
          e.getShopId(), e.getUserId(), e.getStatus(), e.getExitDate(), e.getMembershipEndedAt(),
          e.getUpdatedByUserId(), e.getName())).toList();
      List<AccountState> as = accounts.values().stream()
          .map(a -> new AccountState(a.getUserId(), a.getRole(), a.getShopId())).toList();
      List<MembershipState> ms = memberships.values().stream().map(m -> new MembershipState(
          m.getMembershipId(), m.getUserId(), m.getShopId(), m.getRole(), m.isActive(),
          m.getEndedAt() != null)).toList();
      return new WorldState(es, as, ms);
    }

    // ---- the fake database's tiny query language ----

    private static Map<String, Object> employeeFields(Employee e) {
      Map<String, Object> f = new LinkedHashMap<>();
      f.put("_id", e.getId());
      f.put("status", e.getStatus() == null ? null : e.getStatus().name());
      f.put("exitDate", e.getExitDate());
      f.put("userId", e.getUserId());
      f.put("membershipEndedAt", e.getMembershipEndedAt());
      return f;
    }

    private static Map<String, Object> accountFields(UserAccount a) {
      Map<String, Object> f = new LinkedHashMap<>();
      f.put("_id", a.getUserId());
      f.put("role", a.getRole() == null ? null : a.getRole().name());
      f.put("shopId", a.getShopId());
      return f;
    }

    /** Equality (including {@code null}), {@code $lte} on strings and {@code $type: 2} (string). */
    private static boolean matches(Document where, Map<String, Object> fields) {
      for (Map.Entry<String, Object> clause : where.entrySet()) {
        if (!fields.containsKey(clause.getKey())) {
          throw new UnsupportedOperationException("fake Mongo: unknown field " + clause.getKey());
        }
        Object actual = fields.get(clause.getKey());
        if (clause.getValue() instanceof Document ops) {
          for (Map.Entry<String, Object> op : ops.entrySet()) {
            switch (op.getKey()) {
              case "$lte" -> {
                if (!(actual instanceof String a) || !(op.getValue() instanceof String bound)
                    || a.compareTo(bound) > 0) {
                  return false;
                }
              }
              case "$type" -> {
                if (!(op.getValue() instanceof Number n) || n.intValue() != 2) {
                  throw new UnsupportedOperationException("fake Mongo: $type " + op.getValue());
                }
                if (!(actual instanceof String)) {
                  return false;
                }
              }
              default -> throw new UnsupportedOperationException("fake Mongo: " + op.getKey());
            }
          }
        } else if (!Objects.equals(actual, clause.getValue())) {
          return false;
        }
      }
      return true;
    }

    /** Only {@code $set membershipEndedAt} is allowed on an employee (R5.3: userId stays). */
    private static void applyToEmployee(Employee e, Document update) {
      for (Map.Entry<String, Object> op : update.entrySet()) {
        if (!"$set".equals(op.getKey())) {
          throw new UnsupportedOperationException("fake Mongo: " + op.getKey() + " on employee");
        }
        for (Map.Entry<String, Object> field : ((Document) op.getValue()).entrySet()) {
          if (!"membershipEndedAt".equals(field.getKey())) {
            throw new UnsupportedOperationException(
                "the exit rule must not write Employee." + field.getKey());
          }
          e.setMembershipEndedAt((Instant) field.getValue());
        }
      }
    }

    /** Only {@code $unset shopId} and {@code $set updatedAt} are allowed on an account. */
    private static void applyToAccount(UserAccount a, Document update) {
      for (Map.Entry<String, Object> op : update.entrySet()) {
        Document fields = (Document) op.getValue();
        switch (op.getKey()) {
          case "$unset" -> {
            for (String field : fields.keySet()) {
              if (!"shopId".equals(field)) {
                throw new UnsupportedOperationException(
                    "the exit rule must not unset UserAccount." + field);
              }
              a.setShopId(null);
            }
          }
          case "$set" -> {
            for (Map.Entry<String, Object> field : fields.entrySet()) {
              if (!"updatedAt".equals(field.getKey())) {
                throw new UnsupportedOperationException(
                    "the exit rule must not write UserAccount." + field.getKey());
              }
              a.setUpdatedAt((Instant) field.getValue());
            }
          }
          default -> throw new UnsupportedOperationException("fake Mongo: " + op.getKey());
        }
      }
    }

    private static Employee copy(Employee e) {
      Employee c = new Employee();
      c.setId(e.getId());
      c.setShopId(e.getShopId());
      c.setCode(e.getCode());
      c.setName(e.getName());
      c.setUserId(e.getUserId());
      c.setJoinDate(e.getJoinDate());
      c.setStatus(e.getStatus());
      c.setExitDate(e.getExitDate());
      c.setMembershipEndedAt(e.getMembershipEndedAt());
      c.setUpdatedByUserId(e.getUpdatedByUserId());
      c.setRevision(e.getRevision());
      return c;
    }

    private static UserShopMembership copy(UserShopMembership m) {
      UserShopMembership c = new UserShopMembership();
      c.setMembershipId(m.getMembershipId());
      c.setUserId(m.getUserId());
      c.setShopId(m.getShopId());
      c.setRole(m.getRole());
      c.setRelationship(m.getRelationship());
      c.setActive(m.isActive());
      c.setJoinedAt(m.getJoinedAt());
      c.setEndedAt(m.getEndedAt());
      c.setPermissions(m.getPermissions());
      return c;
    }
  }
}
