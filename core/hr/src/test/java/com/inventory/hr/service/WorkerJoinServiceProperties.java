// Feature: worker-app, Property 3: Shop lookup answers owned shops only, and "not found" is one answer
// Feature: worker-app, Property 7: Possible match
// Feature: worker-app, Property 9: Rejection reason normalisation
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.PhoneNumbers;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.rest.dto.response.JoinRequestDecision;
import com.inventory.hr.rest.dto.response.JoinRequestDetail;
import com.inventory.hr.rest.dto.response.ShopLookupResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.JoinRequestMapperImpl;
import com.inventory.user.service.JoinRequestService;
import com.inventory.user.service.ShopServiceAdapter;
import com.inventory.user.service.UserPhotoService;
import com.inventory.user.service.UserShopMembershipService;
import com.inventory.user.service.WorkerAccountService;
import com.inventory.user.service.api.WorkerAccountsImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.bson.Document;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Properties of {@link WorkerJoinService} that need no database: the shop lookup, the possible
 * match and the rejection reason (worker-app design, task 4.6). Every collaborator is an in-memory
 * fake that answers the way the Mongo query would (a membership finder that filters on {@code
 * active}, an employee finder that filters on shop and status); the user module's lookup chain
 * ({@link WorkerAccountsImpl} → {@link JoinRequestService#getShopsByOwnerEmail}) is the real code,
 * because the OWNER-and-active rule lives there.
 *
 * <p><b>Property 3 — Validates: Requirements 3.2, 3.3, 11.4.</b> For any owner account name and
 * any set of memberships (any account × any shop, OWNER or INVITED, active or ended), {@link
 * WorkerJoinService#lookupShops} answers exactly the shops the account owns through an active OWNER
 * membership, each with the shop's name and {@code ownerFirstName} equal to the first
 * whitespace-separated token of the owner's name, and nothing else; and for any e-mail that owns no
 * shop (no such account, a non-owner, an owner with no shop, a near miss of a real address) the
 * answer is the one empty response.
 *
 * <p><b>Property 7 — Validates: Requirements 4.3.</b> For any shop's employees (ACTIVE or EXITED,
 * linked or not, any e-mail in any letter case, any phone in any written form) and any request
 * snapshot, {@link WorkerJoinService#detail} carries a possible match iff some employee's e-mail
 * equals the snapshot e-mail case-insensitively or some employee's phone equals the snapshot phone
 * after {@link PhoneNumbers#normalise} on both sides; the match is one of the matching employees,
 * the panel carries its code, name, status and {@code linked = (userId != null)}, and the DTO has no
 * other field that could carry contact data. Employees of another shop never match.
 *
 * <p><b>Property 9 — Validates: Requirements 4.7.</b> For any string {@code s}, {@link
 * WorkerJoinService#reject} stores {@code trim(s)} when {@code 1 ≤ len(trim(s)) ≤ 200}, stores
 * nothing when {@code trim(s)} is empty, and refuses with a 400 ({@link ValidationException})
 * before any write when {@code len(trim(s)) > 200}; the stored row carries the approver's user id
 * and the decision time, and the audit row carries the same normalised reason.
 *
 * <p>Tasks 4.7 (stateful life cycle) and 4.8 (audit, concurrency, atomicity) run the service over a
 * real MongoDB replica set and live in their own classes next to this one ({@code
 * WorkerJoinServiceStatefulProperties}, wired by {@code WorkerJoinMongoSupport}); they do not reuse
 * the in-memory wiring here.
 */
class WorkerJoinServiceProperties {

  private static final String SHOP = "shop-1";
  private static final String OTHER_SHOP = "shop-2";
  private static final String APPROVER = "manager-1";
  private static final String WORKER = "worker-1";
  private static final String REQUEST = "req-1";

  // =============================================================================================
  // Property 3: shop lookup
  // =============================================================================================

  /** The shops a membership may name; the shop service knows a name for each. */
  private static final List<String> SHOPS = List.of("shop-a", "shop-b", "shop-c", "shop-d");

  /** An account whose e-mail is a real address and who owns nothing: the "owner with no shop" miss. */
  private static final String NO_SHOP_OWNER = "owner-without-shop";

  /** An account that only ever holds non-owner or ended memberships: the "non-owner" miss. */
  private static final String STAFF_ONLY = "staff-only";

  /** Accounts that may hold any membership the generator draws. */
  private static final List<String> FREE_ACCOUNTS = List.of("acct-0", "acct-1");

  record Membership(String userId, String shopId, String relationship, boolean active) {
    boolean owns() {
      return active && UserShopMembershipService.RELATIONSHIP_OWNER.equals(relationship);
    }
  }

  /**
   * One picture of the user module.
   *
   * @param names each account's display name by user id (null = no name stored)
   * @param memberships every membership row; at most one per (account, shop)
   * @param padding whitespace put around the e-mail the worker types
   */
  record World(List<String> accounts, List<String> names, List<Membership> memberships,
      String padding) {

    static String email(String userId) {
      return userId + "@example.com";
    }

    String nameOf(String userId) {
      return names.get(accounts.indexOf(userId));
    }

    /** What R3.2 promises: the shop ids owned through an active OWNER membership. */
    Set<String> ownedBy(String userId) {
      return memberships.stream()
          .filter(m -> m.userId().equals(userId) && m.owns())
          .map(Membership::shopId)
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  @Provide
  Arbitrary<World> worlds() {
    List<String> accounts = new ArrayList<>(FREE_ACCOUNTS);
    accounts.add(STAFF_ONLY);
    accounts.add(NO_SHOP_OWNER);
    Arbitrary<List<String>> names = ownerNames().list().ofSize(accounts.size());
    // every (free account, shop) pair: no membership, or OWNER / INVITED, active or ended
    List<Arbitrary<Optional<Membership>>> cells = new ArrayList<>();
    for (String account : FREE_ACCOUNTS) {
      for (String shop : SHOPS) {
        cells.add(membershipCell(account, shop, true));
      }
    }
    // the staff-only account may hold anything but an active OWNER row
    for (String shop : SHOPS) {
      cells.add(membershipCell(STAFF_ONLY, shop, false));
    }
    // twelve cells, five values each: no edge-case rounds on this product, or jqwik spends the
    // tries on the all-empty and all-owner corners instead of mixed worlds (names and padding keep
    // theirs: the blank name and the empty padding are the corners that matter)
    Arbitrary<List<Membership>> memberships = Combinators.combine(cells).as(drawn -> {
      List<Membership> rows = new ArrayList<>();
      for (Optional<Membership> cell : drawn) {
        cell.ifPresent(rows::add);
      }
      return rows;
    }).withoutEdgeCases();
    Arbitrary<String> padding = Arbitraries.of("", " ", "  ", "\t", "\n");
    return Combinators.combine(names, memberships, padding)
        .as((n, m, p) -> new World(accounts, n, m, p));
  }

  private static Arbitrary<Optional<Membership>> membershipCell(String account, String shop,
      boolean mayOwn) {
    Arbitrary<String> relationship = Arbitraries.of(UserShopMembershipService.RELATIONSHIP_OWNER,
        UserShopMembershipService.RELATIONSHIP_INVITED);
    Arbitrary<Boolean> active = Arbitraries.of(true, false);
    Arbitrary<Membership> row = Combinators.combine(relationship, active)
        .as((r, a) -> new Membership(account, shop, r, a));
    if (!mayOwn) {
      row = row.filter(m -> !m.owns());
    }
    return row.optional(0.6);
  }

  /** Display names: one to three words of letters joined by whitespace runs, sometimes padded or absent. */
  private static Arbitrary<String> ownerNames() {
    Arbitrary<String> word = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
    Arbitrary<String> gap = Arbitraries.of(" ", "  ", "\t", " \t ");
    Arbitrary<String> edge = Arbitraries.of("", " ", "\t");
    Arbitrary<String> words = Combinators.combine(word.list().ofMinSize(1).ofMaxSize(3), gap)
        .as((ws, g) -> String.join(g, ws));
    Arbitrary<String> named = Combinators.combine(edge, words, edge).as((l, w, r) -> l + w + r);
    return Arbitraries.frequencyOf(
            Tuple.of(8, named),
            Tuple.of(1, Arbitraries.of("", "   ")))
        .injectNull(0.1);
  }

  /** The first whitespace-separated token of a name, as R3.2 words it; null for a blank name. */
  private static String firstToken(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    return name.trim().split("\\s+")[0];
  }

  @Property(tries = 300)
  void lookupAnswersExactlyTheShopsTheAccountOwns(@ForAll("worlds") World w,
      @ForAll("accountIndex") int index) {
    String owner = w.accounts().get(index);
    WorkerJoinService service = lookupService(w);

    ShopLookupResponse response =
        service.lookupShops(WORKER, w.padding() + World.email(owner) + w.padding());

    Set<String> expected = w.ownedBy(owner);
    List<String> answered = response.getShops().stream()
        .map(ShopLookupResponse.Shop::getShopId).toList();
    assertEquals(expected, new TreeSet<>(answered), "owned shops only: " + w);
    assertEquals(expected.size(), answered.size(), "each owned shop once: " + w);
    for (ShopLookupResponse.Shop shop : response.getShops()) {
      assertEquals(shopName(shop.getShopId()), shop.getShopName());
      assertEquals(firstToken(w.nameOf(owner)), shop.getOwnerFirstName(),
          "first token of " + w.nameOf(owner));
    }
  }

  @Provide
  Arbitrary<Integer> accountIndex() {
    return Arbitraries.integers().between(0, FREE_ACCOUNTS.size() + 1);
  }

  @Property(tries = 300)
  void everyEmailThatOwnsNoShopGetsTheOneEmptyAnswer(@ForAll("worlds") World w,
      @ForAll("unknownEmails") String unknown) {
    WorkerJoinService service = lookupService(w);
    ShopLookupResponse empty = new ShopLookupResponse(List.of());

    // no such account
    assertEquals(empty, service.lookupShops(WORKER, unknown), "unknown e-mail: " + unknown);
    // a real address, off by its last character: not an exact match (R11.4)
    String nearMiss = World.email(FREE_ACCOUNTS.get(0));
    nearMiss = nearMiss.substring(0, nearMiss.length() - 1);
    assertEquals(empty, service.lookupShops(WORKER, nearMiss), "near miss: " + nearMiss);
    // an owner account with no shop at all
    assertEquals(empty, service.lookupShops(WORKER, World.email(NO_SHOP_OWNER)));
    // an account with memberships, none of them an active OWNER one
    assertEquals(empty, service.lookupShops(WORKER, World.email(STAFF_ONLY)),
        "non-owner memberships: " + w.memberships());
    // and every free account that happens to own nothing in this world
    for (String account : FREE_ACCOUNTS) {
      if (w.ownedBy(account).isEmpty()) {
        assertEquals(empty, service.lookupShops(WORKER, World.email(account)), account);
      }
    }
  }

  @Provide
  Arbitrary<String> unknownEmails() {
    Arbitrary<String> local = Arbitraries.strings().withCharRange('a', 'z').withChars("._-")
        .ofMinLength(1).ofMaxLength(12);
    Arbitrary<String> domain = Arbitraries.of("example.org", "mail.test", "example.com.au");
    return Combinators.combine(local, domain).as((l, d) -> l + "@" + d)
        .filter(e -> !e.endsWith("@example.com"));
  }

  /** The shop service's name for a shop; the lookup must pass it through unchanged. */
  private static String shopName(String shopId) {
    return "Shop " + shopId.substring(shopId.indexOf('-') + 1).toUpperCase(Locale.ROOT);
  }

  // =============================================================================================
  // Property 7: possible match
  // =============================================================================================

  private static final List<String> EMAILS =
      List.of("ravi@example.com", "priya@example.com", "anil@example.com");
  private static final List<String> PHONES = List.of("9876543210", "9123456789", "9000000001");

  /** One employee as the generator draws it; {@code shopId} decides whether it is a candidate. */
  record Emp(String id, String code, String shopId, EmployeeStatus status, boolean linked,
      String email, String phone) {
    Employee toEmployee() {
      Employee e = new Employee();
      e.setId(id);
      e.setShopId(shopId);
      e.setCode(code);
      e.setName("Employee " + code);
      e.setStatus(status);
      e.setUserId(linked ? "login-" + id : null);
      e.setEmail(email);
      e.setPhone(phone);
      return e;
    }
  }

  record MatchScenario(List<Emp> employees, String snapshotEmail, String snapshotPhone) {

    /** R4.3 word for word: e-mail equal ignoring case, or phone equal after normalisation. */
    boolean agrees(Emp e) {
      boolean byEmail = snapshotEmail != null && e.email() != null
          && snapshotEmail.equalsIgnoreCase(e.email());
      Optional<String> phone = PhoneNumbers.normalise(snapshotPhone);
      boolean byPhone = phone.isPresent() && PhoneNumbers.normalise(e.phone()).equals(phone);
      return byEmail || byPhone;
    }

    /** A match the inbox may show: one of this shop's employees that agrees. */
    boolean matches(Emp e) {
      return SHOP.equals(e.shopId()) && agrees(e);
    }

    Set<String> matchingIds() {
      return employees.stream().filter(this::matches).map(Emp::id)
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  @Provide
  Arbitrary<MatchScenario> matchScenarios() {
    Arbitrary<String> emails = anyCase(Arbitraries.of(EMAILS)).injectNull(0.2);
    Arbitrary<String> phones = writtenPhones().injectNull(0.15);
    Arbitrary<Emp> inShop = employee(SHOP, emails, phones);
    Arbitrary<Emp> elsewhere = employee(OTHER_SHOP, emails, phones);
    // the eight dimensions multiply; without edge-case rounds the tries spread over the space
    Arbitrary<List<Emp>> employees = Combinators.combine(
            inShop.list().ofMaxSize(5).withoutEdgeCases(),
            elsewhere.list().ofMaxSize(2).withoutEdgeCases())
        .as((here, there) -> {
          List<Emp> all = new ArrayList<>();
          int i = 0;
          for (Emp e : here) {
            all.add(new Emp("emp-" + i, "EMP-" + String.format("%03d", i), e.shopId(),
                e.status(), e.linked(), e.email(), e.phone()));
            i++;
          }
          for (Emp e : there) {
            all.add(new Emp("emp-" + i, "EMP-" + String.format("%03d", i), e.shopId(),
                e.status(), e.linked(), e.email(), e.phone()));
            i++;
          }
          return all;
        });
    return Combinators.combine(employees, emails, phones).as(MatchScenario::new);
  }

  private static Arbitrary<Emp> employee(String shopId, Arbitrary<String> emails,
      Arbitrary<String> phones) {
    return Combinators.combine(Arbitraries.of(EmployeeStatus.class), Arbitraries.of(true, false),
            emails, phones)
        .as((status, linked, email, phone) ->
            new Emp(null, null, shopId, status, linked, email, phone));
  }

  /** The same address in any mix of upper and lower case. */
  private static Arbitrary<String> anyCase(Arbitrary<String> base) {
    return base.flatMap(s -> Arbitraries.of(true, false).list().ofSize(s.length()).map(flags -> {
      StringBuilder out = new StringBuilder(s.length());
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        out.append(flags.get(i) ? Character.toUpperCase(c) : Character.toLowerCase(c));
      }
      return out.toString();
    }));
  }

  /**
   * A phone as a person might write it: one of the pool numbers bare, with {@code +91}, {@code 91},
   * spaces or hyphens (all the same number under R1.2), or something that is not an Indian mobile
   * (a trunk {@code 0}, too few digits, a different number's digits).
   */
  private static Arbitrary<String> writtenPhones() {
    Arbitrary<String> valid = Arbitraries.of(PHONES).flatMap(n -> Arbitraries.of(
        n,
        "+91 " + n.substring(0, 5) + " " + n.substring(5),
        "91" + n,
        "+91-" + n,
        n.substring(0, 5) + "-" + n.substring(5),
        "(" + n.substring(0, 3) + ") " + n.substring(3)));
    Arbitrary<String> invalid = Arbitraries.of(PHONES).flatMap(n -> Arbitraries.of(
        "0" + n, n.substring(1), "+1 " + n, "", "abc"));
    return Arbitraries.frequencyOf(Tuple.of(5, valid), Tuple.of(1, invalid));
  }

  @Property(tries = 300)
  void detailCarriesAPossibleMatchIffAnEmailOrPhoneAgrees(@ForAll("matchScenarios") MatchScenario s) {
    WorkerJoinRequest request = pendingRequest(s.snapshotEmail(), s.snapshotPhone());
    WorkerJoinService service = matchService(s, request);

    JoinRequestDetail detail = service.detail(APPROVER, SHOP, REQUEST);

    Set<String> matching = s.matchingIds();
    assertEquals(!matching.isEmpty(), detail.getPossibleMatch() != null, "match iff: " + s);
    assertEquals(!matching.isEmpty(), detail.isHasPossibleMatch(), "row flag agrees: " + s);
    if (detail.getPossibleMatch() != null) {
      JoinRequestDetail.PossibleMatch panel = detail.getPossibleMatch();
      assertTrue(matching.contains(panel.getEmployeeId()),
          panel.getEmployeeId() + " is not one of the matching employees " + matching);
      Emp chosen = s.employees().stream().filter(e -> e.id().equals(panel.getEmployeeId()))
          .findFirst().orElseThrow();
      assertEquals(chosen.code(), panel.getCode());
      assertEquals("Employee " + chosen.code(), panel.getName());
      assertEquals(chosen.status(), panel.getStatus());
      assertEquals(chosen.linked(), panel.isLinked(), "linked = (userId != null)");
    }
  }

  @Property(tries = 300)
  void theStaticRuleAgreesWithTheDetailAndIgnoresOtherShops(
      @ForAll("matchScenarios") MatchScenario s) {
    WorkerJoinRequest request = pendingRequest(s.snapshotEmail(), s.snapshotPhone());
    List<Employee> shopEmployees = s.employees().stream()
        .filter(e -> SHOP.equals(e.shopId())).map(Emp::toEmployee).toList();
    List<Employee> everyEmployee = s.employees().stream().map(Emp::toEmployee).toList();

    Optional<Employee> fromShop = WorkerJoinService.possibleMatch(request, shopEmployees);
    Set<String> matching = s.matchingIds();
    assertEquals(!matching.isEmpty(), fromShop.isPresent(), s.toString());
    fromShop.ifPresent(e -> assertTrue(matching.contains(e.getId())));

    // an employee of another shop matches only when handed in as a candidate, which the shop-scoped
    // query never does: the rule itself is shop-blind, so widening the candidates widens the answer
    Optional<Employee> fromAll = WorkerJoinService.possibleMatch(request, everyEmployee);
    assertEquals(s.employees().stream().anyMatch(s::agrees), fromAll.isPresent());
    assertTrue(WorkerJoinService.possibleMatch(request, List.of()).isEmpty());
    assertTrue(WorkerJoinService.possibleMatch(null, shopEmployees).isEmpty());
  }

  /** The panel's shape is fixed: an id to link by, code, name, status, linked; no contact data. */
  @Example
  void thePossibleMatchPanelHasNoRoomForContactData() {
    Set<String> fields = Arrays.stream(JoinRequestDetail.PossibleMatch.class.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
        .map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(new TreeSet<>(List.of("employeeId", "code", "name", "status", "linked")), fields);
  }

  // =============================================================================================
  // Property 9: rejection reason normalisation
  // =============================================================================================

  @Provide
  Arbitrary<String> reasons() {
    // whitespace String.trim() removes: every char up to and including U+0020
    Arbitrary<String> pad = Arbitraries.strings().withChars(" \t\n\r\u000B\f\u0000")
        .ofMaxLength(4);
    Arbitrary<String> anyText = Arbitraries.strings().ofMaxLength(230);
    // the 200-character edge, with visible characters only so the trimmed length is the drawn one
    Arbitrary<String> edgeText = Arbitraries.of(0, 1, 199, 200, 201, 202).flatMap(n ->
        Arbitraries.strings().withCharRange('!', '~').ofLength(n));
    Arbitrary<String> text = Arbitraries.frequencyOf(Tuple.of(2, anyText), Tuple.of(1, edgeText));
    Arbitrary<String> padded = Combinators.combine(pad, text, pad).as((l, t, r) -> l + t + r);
    return padded.injectNull(0.05);
  }

  /** What R4.7 asks the row to carry: {@code trim(s)}, or nothing for a blank reason. */
  private static String expectedReason(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  @Property(tries = 400)
  void rejectStoresTheTrimmedReasonOrNothingAndRefusesOver200(@ForAll("reasons") String reason) {
    RejectWiring w = rejectService();
    String expected = expectedReason(reason);
    Instant before = Instant.now();

    if (expected != null && expected.length() > WorkerJoinService.MAX_REASON_LENGTH) {
      ValidationException refused = assertThrows(ValidationException.class,
          () -> w.service().reject(APPROVER, SHOP, REQUEST, reason));

      assertEquals(ErrorCode.VALIDATION_ERROR, refused.getErrorCode());
      assertEquals(400, refused.getErrorCode().getHttpStatus().value());
      assertTrue(refused.getMessage().contains(WorkerJoinService.REASON_TOO_LONG),
          refused.getMessage());
      verify(w.mongoTemplate(), never()).findAndModify(any(Query.class), any(Update.class),
          any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
      verifyNoInteractions(w.audit(), w.notifications());
      return;
    }

    JoinRequestDecision decision = w.service().reject(APPROVER, SHOP, REQUEST, reason);
    Instant after = Instant.now();

    assertEquals(JoinOutcome.REJECTED, decision.getOutcome());
    assertEquals(REQUEST, decision.getId());
    Update update = w.update().get();
    assertNotNull(update, "one conditional write");
    Document set = (Document) update.getUpdateObject().get("$set");
    Document unset = (Document) update.getUpdateObject().get("$unset");
    if (expected == null) {
      assertFalse(set.containsKey("rejectionReason"), "blank reason stored as none");
      assertNotNull(unset);
      assertTrue(unset.containsKey("rejectionReason"));
    } else {
      assertEquals(expected, set.get("rejectionReason"), "trim(s) stored word for word");
      assertTrue(expected.length() >= 1 && expected.length() <= 200);
    }
    assertEquals(WorkerJoinStatus.REJECTED, set.get("status"));
    assertEquals(JoinOutcome.REJECTED, set.get("outcome"));
    assertEquals(APPROVER, set.get("decidedByUserId"), "the approver's user id");
    Instant decidedAt = (Instant) set.get("decidedAt");
    assertNotNull(decidedAt, "the decision time");
    assertFalse(decidedAt.isBefore(before) || decidedAt.isAfter(after),
        decidedAt + " within [" + before + ", " + after + "]");
    assertEquals(decidedAt.plus(WorkerJoinService.RETENTION), set.get("expiresAt"));
    Document query = w.query().get().getQueryObject();
    assertEquals(REQUEST, query.get("_id"));
    assertEquals(SHOP, query.get("shopId"));
    assertEquals(WorkerJoinStatus.PENDING, query.get("status"), "only a Pending request");

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(w.audit()).record(audit.capture());
    assertEquals(HrAuditAction.JOIN_REQUEST_REJECTED, audit.getValue().action());
    assertEquals(expected, audit.getValue().reason(), "the audit row carries the same reason");
    assertEquals(APPROVER, audit.getValue().actorUserId());
  }

  @Property(tries = 400)
  void normaliseReasonIsTrimThenEmptyToNullThenLengthCheck(@ForAll("reasons") String reason) {
    String expected = expectedReason(reason);
    if (expected != null && expected.length() > WorkerJoinService.MAX_REASON_LENGTH) {
      assertThrows(ValidationException.class, () -> WorkerJoinService.normaliseReason(reason));
    } else {
      assertEquals(expected, WorkerJoinService.normaliseReason(reason));
      // idempotent: a stored reason normalises to itself
      assertEquals(expected, WorkerJoinService.normaliseReason(expected));
    }
  }

  // =============================================================================================
  // wiring (in-memory; the Mongo-backed wiring of tasks 4.7 and 4.8 is WorkerJoinMongoSupport)
  // =============================================================================================

  /**
   * The lookup chain as deployed: {@link WorkerJoinService} → {@link WorkerAccountsImpl} → {@link
   * JoinRequestService#getShopsByOwnerEmail}, over fakes of the two repositories and the shop
   * service that answer from the world.
   */
  private static WorkerJoinService lookupService(World w) {
    List<UserAccount> accounts = new ArrayList<>();
    for (String userId : w.accounts()) {
      UserAccount a = new UserAccount();
      a.setUserId(userId);
      a.setEmail(World.email(userId));
      a.setName(w.nameOf(userId));
      a.setRole(NO_SHOP_OWNER.equals(userId) ? UserRole.OWNER
          : STAFF_ONLY.equals(userId) ? UserRole.CASHIER : UserRole.OWNER);
      a.setActive(true);
      accounts.add(a);
    }
    UserAccountRepository users = mock(UserAccountRepository.class);
    when(users.findByEmail(anyString())).thenAnswer(inv -> {
      String email = inv.getArgument(0);
      return accounts.stream().filter(a -> a.getEmail().equals(email)).findFirst();
    });
    when(users.findById(anyString())).thenAnswer(inv -> {
      String id = inv.getArgument(0);
      return accounts.stream().filter(a -> a.getUserId().equals(id)).findFirst();
    });

    List<UserShopMembership> rows = new ArrayList<>();
    for (Membership m : w.memberships()) {
      UserShopMembership row = new UserShopMembership();
      row.setMembershipId(m.userId() + ":" + m.shopId());
      row.setUserId(m.userId());
      row.setShopId(m.shopId());
      row.setRole(UserShopMembershipService.RELATIONSHIP_OWNER.equals(m.relationship())
          ? UserRole.OWNER : UserRole.CASHIER);
      row.setRelationship(m.relationship());
      row.setActive(m.active());
      rows.add(row);
    }
    UserShopMembershipRepository memberships = mock(UserShopMembershipRepository.class);
    // the derived query's contract: this user's rows with active = true, nothing else filtered
    when(memberships.findByUserIdAndActiveTrue(anyString())).thenAnswer(inv -> {
      String userId = inv.getArgument(0);
      return rows.stream().filter(r -> r.getUserId().equals(userId) && r.isActive()).toList();
    });

    ShopServiceAdapter shops = mock(ShopServiceAdapter.class);
    when(shops.getShopName(anyString())).thenAnswer(inv -> shopName(inv.getArgument(0)));

    JoinRequestService staffJoin = new JoinRequestService();
    ReflectionTestUtils.setField(staffJoin, "userAccountRepository", users);
    ReflectionTestUtils.setField(staffJoin, "membershipRepository", memberships);
    ReflectionTestUtils.setField(staffJoin, "shopServiceAdapter", shops);
    ReflectionTestUtils.setField(staffJoin, "joinRequestMapper", new JoinRequestMapperImpl());

    WorkerAccounts workerAccounts = new WorkerAccountsImpl(users,
        mock(WorkerAccountService.class), mock(UserShopMembershipService.class),
        mock(UserPhotoService.class), staffJoin);

    WorkerJoinService service = new WorkerJoinService();
    ReflectionTestUtils.setField(service, "workerAccounts", workerAccounts);
    ReflectionTestUtils.setField(service, "rateLimiter", openRateLimiter());
    ReflectionTestUtils.setField(service, "counters", mock(HrWorkerCounters.class));
    return service;
  }

  /** A service whose employee finder answers the shop-and-status query from the scenario. */
  private static WorkerJoinService matchService(MatchScenario s, WorkerJoinRequest request) {
    List<Employee> employees = s.employees().stream().map(Emp::toEmployee).toList();
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    when(employeeRepository.findMatchCandidates(anyString(), anyCollection())).thenAnswer(inv -> {
      String shopId = inv.getArgument(0);
      Collection<EmployeeStatus> statuses = inv.getArgument(1);
      return employees.stream()
          .filter(e -> shopId.equals(e.getShopId()) && statuses.contains(e.getStatus()))
          .toList();
    });
    when(employeeRepository.findAllById(anyCollection())).thenReturn(List.of());

    WorkerJoinRequestRepository requestRepository = mock(WorkerJoinRequestRepository.class);
    when(requestRepository.findByShopIdAndId(SHOP, REQUEST)).thenReturn(Optional.of(request));

    WorkerJoinService service = new WorkerJoinService();
    ReflectionTestUtils.setField(service, "employeeRepository", employeeRepository);
    ReflectionTestUtils.setField(service, "requestRepository", requestRepository);
    ReflectionTestUtils.setField(service, "guard", mock(HrPermissionGuard.class));
    return service;
  }

  /** The reject path's collaborators, with the one conditional write captured. */
  record RejectWiring(WorkerJoinService service, MongoTemplate mongoTemplate,
      HrAuditService audit, HrNotificationService notifications,
      AtomicReference<Query> query, AtomicReference<Update> update) {}

  private static RejectWiring rejectService() {
    AtomicReference<Query> query = new AtomicReference<>();
    AtomicReference<Update> update = new AtomicReference<>();
    MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenAnswer(inv -> {
          query.set(inv.getArgument(0));
          Update u = inv.getArgument(1);
          update.set(u);
          // the row as the database would hand it back after the one conditional write
          WorkerJoinRequest row = pendingRequest("priya@example.com", "9876543210");
          Document set = (Document) u.getUpdateObject().get("$set");
          row.setStatus((WorkerJoinStatus) set.get("status"));
          row.setOutcome((JoinOutcome) set.get("outcome"));
          row.setDecidedAt((Instant) set.get("decidedAt"));
          row.setDecidedByUserId((String) set.get("decidedByUserId"));
          row.setExpiresAt((Instant) set.get("expiresAt"));
          row.setRejectionReason((String) set.get("rejectionReason"));
          return row;
        });
    HrAuditService audit = mock(HrAuditService.class);
    HrNotificationService notifications = mock(HrNotificationService.class);
    ShopNamePort shopNames = mock(ShopNamePort.class);
    when(shopNames.getShopName(anyString())).thenReturn("Sri Ganesh Stores");

    WorkerJoinService service = new WorkerJoinService();
    ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
    ReflectionTestUtils.setField(service, "requestRepository",
        mock(WorkerJoinRequestRepository.class));
    ReflectionTestUtils.setField(service, "guard", mock(HrPermissionGuard.class));
    ReflectionTestUtils.setField(service, "auditService", audit);
    ReflectionTestUtils.setField(service, "notificationService", notifications);
    ReflectionTestUtils.setField(service, "shopNamePort", shopNames);
    ReflectionTestUtils.setField(service, "counters", mock(HrWorkerCounters.class));
    return new RejectWiring(service, mongoTemplate, audit, notifications, query, update);
  }

  private static AppRateLimiter openRateLimiter() {
    AppRateLimiter limiter = mock(AppRateLimiter.class);
    when(limiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    return limiter;
  }

  private static WorkerJoinRequest pendingRequest(String email, String phone) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId(REQUEST);
    r.setShopId(SHOP);
    r.setUserId(WORKER);
    r.setStatus(WorkerJoinStatus.PENDING);
    r.setSnapshot(new WorkerJoinRequest.Snapshot("Priya Sharma", phone, email, false));
    r.setRequestedAt(Instant.parse("2025-05-01T04:00:00Z"));
    r.setUpdatedAt(r.getRequestedAt());
    return r;
  }
}
