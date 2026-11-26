// Feature: worker-app, Property 19: Edit details reaches both records with one audit row
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.PhoneNumbers;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.mapper.EmployeeMapperImpl;
import com.inventory.hr.rest.dto.response.WorkerProfileResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.inventory.user.service.UserShopMembershipService;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Property 19 of the worker-app design: for any valid name and phone, "Edit details" ({@link
 * WorkerSelfService#updateProfile}, the service behind {@code PUT /hr/worker/profile}) leaves the
 * account and, when one exists, the linked employee with the same normalised name (trimmed) and
 * phone (the 10 digits of {@link PhoneNumbers#normalise}); it writes exactly one {@code
 * EMPLOYEE_CHANGED} audit row with actor {@code self} whose {@code before}/{@code after} carry the
 * old and the new name and whose {@code after} flags a changed phone ({@code phoneChanged}; the
 * number itself is PII and stays out of the row), and it changes no other employee field and no
 * other employee. An invalid name or phone (Property 1) is refused with a {@code
 * ValidationException} and changes nothing on either record and writes no audit row (R8.2).
 *
 * <p>The account side is an in-memory {@link WorkerAccounts} under the contract of its Javadoc
 * (name 2–100 after trimming Unicode whitespace, phone through {@code PhoneNumbers.normalise});
 * the employee side is the real {@link EmployeeService} and {@link EmployeeValidator} over an
 * in-memory employee store and a recording audit service.
 *
 * <p><b>Validates: Requirements 8.2</b>
 */
class EmployeeServiceSelfEditProperties {

  private static final Instant NOW = Instant.parse("2025-04-09T09:12:00Z");
  private static final String WORKER = "user-w";
  private static final String OTHER_USER = "user-o";

  /** The employee fields a self edit may touch; every other field must be as before. */
  private static final Set<String> EDITABLE = Set.of("name", "phone", "updatedAt",
      "updatedByUserId");

  /** ASCII whitespace and Unicode spaces (em space, ideographic space); all of it is trimmed. */
  private static final String WHITESPACE = " \t\n\u2003\u3000";

  // ---- generators ----------------------------------------------------------------------------

  /** A name as typed: a 2–100 character core with whitespace around it. */
  @Provide
  Arbitrary<String> validNames() {
    Arbitrary<String> core = Arbitraries.strings()
        .withCharRange('a', 'z').withCharRange('A', 'Z').withChars(" .'-")
        .withChars("ÀÉÖñüकखग")
        .ofMinLength(2).ofMaxLength(100)
        .filter(s -> s.strip().length() >= 2);
    return Combinators.combine(padding(), core, padding()).as((l, c, r) -> l + c + r);
  }

  /** A name the R1.2 rule refuses: null, blank, one character, more than 100. */
  @Provide
  Arbitrary<String> invalidNames() {
    Arbitrary<String> blank = Arbitraries.strings().withChars(WHITESPACE).ofMaxLength(5);
    Arbitrary<String> one = Combinators.combine(padding(),
        Arbitraries.strings().withCharRange('a', 'z').ofLength(1), padding())
        .as((l, c, r) -> l + c + r);
    Arbitrary<String> tooLong = Arbitraries.strings().withCharRange('a', 'z')
        .ofMinLength(101).ofMaxLength(140);
    return Arbitraries.oneOf(blank, one, tooLong).injectNull(0.1);
  }

  /** A phone as typed: 10 digits, with or without 91 / +91 and separators. */
  @Provide
  Arbitrary<String> validPhones() {
    Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofLength(10);
    Arbitrary<String> prefix = Arbitraries.of("", "+91", "91", "+91 ", "91-", "(+91)");
    Arbitrary<Boolean> spaced = Arbitraries.of(true, false);
    return Combinators.combine(prefix, digits, spaced).as((p, d, sp) ->
        p + (sp ? d.substring(0, 5) + " " + d.substring(5) : d));
  }

  /** A phone the rule refuses: null, blank, too few or too many digits, no digits. */
  @Provide
  Arbitrary<String> invalidPhones() {
    Arbitrary<String> few = Arbitraries.strings().withCharRange('0', '9').ofMinLength(1)
        .ofMaxLength(9);
    Arbitrary<String> eleven = Arbitraries.strings().withCharRange('0', '9').ofLength(11);
    Arbitrary<String> twelveNot91 = Arbitraries.strings().withCharRange('0', '9').ofLength(12)
        .filter(s -> !s.startsWith("91"));
    Arbitrary<String> letters = Arbitraries.strings().withCharRange('a', 'z').ofMaxLength(12);
    return Arbitraries.oneOf(few, eleven, twelveNot91, letters).injectNull(0.1);
  }

  /** Whether the caller has a linked ACTIVE employee, and the employee's current phone. */
  record Link(boolean linked, String currentPhone, String currentName) {}

  @Provide
  Arbitrary<Link> links() {
    // the stored phone is sometimes the one being set, so the phoneChanged flag goes both ways
    Arbitrary<String> phone = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.strings().withCharRange('0', '9').ofLength(10)),
        Tuple.of(1, Arbitraries.just("9876543210")));
    Arbitrary<String> name = Arbitraries.of("Ravi Kumar", "Priya Sharma", "A. B. Chandran");
    return Combinators.combine(Arbitraries.frequencyOf(
            Tuple.of(3, Arbitraries.just(true)), Tuple.of(1, Arbitraries.just(false))),
        phone, name).as(Link::new);
  }

  private static Arbitrary<String> padding() {
    return Arbitraries.strings().withChars(WHITESPACE).ofMaxLength(3);
  }

  // ---- properties ----------------------------------------------------------------------------

  @Property(tries = 300)
  void aValidEditReachesBothRecordsWithTheSameValuesAndOneSelfAuditRow(
      @ForAll("validNames") String name, @ForAll("validPhones") String phone,
      @ForAll("links") Link link) {
    Harness h = new Harness(link);
    Map<String, Object> ownBefore = h.own != null ? fields(h.own) : null;
    Map<String, Object> otherBefore = fields(h.other);
    String expectedName = name.strip();
    String expectedPhone = PhoneNumbers.normalise(phone).orElseThrow();

    WorkerProfileResponse answer = h.service.updateProfile(WORKER, name, phone);

    String context = "name=<" + name + "> phone=<" + phone + "> " + link;
    // the account
    AccountView account = h.accounts.store.get(WORKER);
    assertEquals(expectedName, account.name(), context);
    assertEquals(expectedPhone, account.phone(), context);
    assertEquals(expectedName, answer.getName(), context);
    assertEquals(expectedPhone, answer.getPhone(), context);
    assertEquals("w@example.com", account.email(), "the e-mail is never changed: " + context);
    // the other employee, whoever edits
    assertEquals(otherBefore, fields(h.other), "another employee changed: " + context);

    if (!link.linked()) {
      assertEquals(List.of(), h.audits, "no employee, no audit row: " + context);
      assertEquals(0, h.saves, "no employee, no save: " + context);
      assertNull(answer.getEmployeeCode(), context);
      return;
    }

    // the employee: the same values, actor self, nothing else
    Employee own = h.own;
    assertEquals(expectedName, own.getName(), context);
    assertEquals(expectedPhone, own.getPhone(), context);
    assertEquals(EmployeeService.SELF_ACTOR, own.getUpdatedByUserId(), context);
    assertEquals(1, h.saves, "one save: " + context);
    Map<String, Object> ownAfter = fields(own);
    for (Map.Entry<String, Object> e : ownBefore.entrySet()) {
      if (!EDITABLE.contains(e.getKey())) {
        assertEquals(e.getValue(), ownAfter.get(e.getKey()),
            "field " + e.getKey() + " changed by a self edit: " + context);
      }
    }
    assertEquals(own.getCode(), answer.getEmployeeCode(), context);

    // exactly one audit row, actor self, before/after name, the phone as a flag only
    assertEquals(1, h.audits.size(), "exactly one audit row: " + context);
    AuditEntry row = h.audits.get(0);
    assertEquals(HrAuditAction.EMPLOYEE_CHANGED, row.action(), context);
    assertEquals(EmployeeService.SELF_ACTOR, row.actorUserId(), context);
    assertEquals(SHOP, row.shopId(), context);
    assertEquals(own.getId(), row.entityId(), context);
    assertEquals(own.getId(), row.employeeId(), context);
    assertEquals(link.currentName(), row.before().get("name"), context);
    assertEquals(expectedName, row.after().get("name"), context);
    boolean phoneChanged = !Objects.equals(link.currentPhone(), expectedPhone);
    assertEquals(phoneChanged ? Boolean.TRUE : null,
        row.after().get(EmployeeService.PHONE_CHANGED), "phoneChanged flag: " + context);
    assertNull(row.before().get(EmployeeService.PHONE_CHANGED), context);
    String text = row.before() + " " + row.after();
    assertFalse(text.contains(expectedPhone), "the new number is in the audit row: " + context);
    assertFalse(text.contains(link.currentPhone()), "the old number is in the audit row: "
        + context);
  }

  @Property(tries = 300)
  void anInvalidEditIsRefusedAndChangesNothing(
      @ForAll("invalidNames") String badName, @ForAll("validNames") String goodName,
      @ForAll("invalidPhones") String badPhone, @ForAll("validPhones") String goodPhone,
      @ForAll boolean nameIsBad, @ForAll boolean phoneIsBad, @ForAll("links") Link link) {
    Assume.that(nameIsBad || phoneIsBad);
    String name = nameIsBad ? badName : goodName;
    String phone = phoneIsBad ? badPhone : goodPhone;
    Harness h = new Harness(link);
    AccountView accountBefore = h.accounts.store.get(WORKER);
    Map<String, Object> ownBefore = h.own != null ? fields(h.own) : null;
    Map<String, Object> otherBefore = fields(h.other);
    String context = "name=<" + name + "> phone=<" + phone + "> " + link;

    assertThrows(ValidationException.class, () -> h.service.updateProfile(WORKER, name, phone),
        context);

    assertEquals(accountBefore, h.accounts.store.get(WORKER), "account changed: " + context);
    if (h.own != null) {
      assertEquals(ownBefore, fields(h.own), "employee changed: " + context);
    }
    assertEquals(otherBefore, fields(h.other), "another employee changed: " + context);
    assertEquals(List.of(), h.audits, "an audit row for a refused edit: " + context);
    assertEquals(0, h.saves, "a save for a refused edit: " + context);
  }

  @Property(tries = 200)
  void theEmployeeValidatorAcceptsExactlyWhatTheAccountAccepts(
      @ForAll("validNames") String validName, @ForAll("invalidNames") String invalidName,
      @ForAll("validPhones") String validPhone, @ForAll("invalidPhones") String invalidPhone) {
    // Property 1 on the employee side: the self-edit validator and the account rule agree, so a
    // value the account has already saved is never refused by the employee half of the edit
    EmployeeValidator validator = new EmployeeValidator();
    EmployeeValidator.SelfDetails ok = validator.validateSelfDetails(validName, validPhone);
    assertEquals(validName.strip(), ok.name(), "<" + validName + ">");
    assertEquals(PhoneNumbers.normalise(validPhone).orElseThrow(), ok.phone());
    assertThrows(ValidationException.class,
        () -> validator.validateSelfDetails(invalidName, validPhone), "<" + invalidName + ">");
    assertThrows(ValidationException.class,
        () -> validator.validateSelfDetails(validName, invalidPhone), "<" + invalidPhone + ">");
  }

  // ---- wiring --------------------------------------------------------------------------------

  /** Every non-static field of an employee by name, for a before/after comparison. */
  private static Map<String, Object> fields(Employee e) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Field f : Employee.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers())) {
        continue;
      }
      out.put(f.getName(), ReflectionTestUtils.getField(e, f.getName()));
    }
    // the revisions list is mutable; compare by value
    out.put("salaryRevisions", e.getSalaryRevisions() == null ? null
        : new ArrayList<>(e.getSalaryRevisions()));
    return out;
  }

  /**
   * The account side under the {@link WorkerAccounts#updateProfile} contract: trimming is Unicode
   * whitespace, 2–100 characters, the phone through {@link PhoneNumbers#normalise}; the e-mail is
   * left alone.
   */
  static final class InMemoryAccounts implements WorkerAccounts {
    final Map<String, AccountView> store = new LinkedHashMap<>();

    @Override
    public Optional<AccountView> account(String userId) {
      return Optional.ofNullable(store.get(userId));
    }

    @Override
    public List<OwnedShop> shopsOwnedBy(String ownerEmail) {
      return List.of();
    }

    @Override
    public boolean claimShop(String shopId, String userId) {
      return false;
    }

    @Override
    public boolean releaseShop(String shopId, String userId) {
      return false;
    }

    @Override
    public void addWorkerMembership(String shopId, String userId) {}

    @Override
    public boolean hasPhoto(String userId) {
      return false;
    }

    @Override
    public AccountView updateProfile(String userId, String name, String phone) {
      String cleanName = name == null ? "" : name.strip();
      if (cleanName.length() < 2 || cleanName.length() > 100) {
        throw new ValidationException("Name must be between 2 and 100 characters");
      }
      if (phone == null || phone.isBlank()) {
        throw new ValidationException("Phone is required");
      }
      String cleanPhone = PhoneNumbers.normalise(phone).orElseThrow(() ->
          new ValidationException("Phone must be a valid Indian number (10 digits or +91 format)"));
      AccountView old = store.get(userId);
      AccountView saved = new AccountView(old.userId(), cleanName, old.email(), cleanPhone,
          old.role(), old.shopId(), old.worker());
      store.put(userId, saved);
      return saved;
    }
  }

  /** The real HR edit path over an in-memory employee store and a recording audit service. */
  static final class Harness {
    final WorkerSelfService service = new WorkerSelfService();
    final InMemoryAccounts accounts = new InMemoryAccounts();
    final List<AuditEntry> audits = new ArrayList<>();
    final List<Employee> employees = new ArrayList<>();
    /** The caller's employee, or null. */
    final Employee own;
    /** Somebody else's employee in the same shop, which no self edit may touch. */
    final Employee other;
    int saves;

    Harness(Link link) {
      accounts.store.put(WORKER, new AccountView(WORKER, "Old Name", "w@example.com",
          "9876543210", "WORKER", link.linked() ? SHOP : null, true));

      other = HrTestFixtures.employee("e-other");
      other.setUserId(OTHER_USER);
      other.setName("Other Person");
      other.setPhone("9000000001");
      employees.add(other);
      if (link.linked()) {
        own = HrTestFixtures.employee("e-own");
        own.setUserId(WORKER);
        own.setName(link.currentName());
        own.setPhone(link.currentPhone());
        own.setStatus(EmployeeStatus.ACTIVE);
        own.setProfileRequestedAt(NOW);
        employees.add(own);
      } else {
        own = null;
      }

      EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
      when(employeeRepository.findByUserIdAndStatus(anyString(), any())).thenAnswer(inv -> {
        String userId = inv.getArgument(0);
        EmployeeStatus status = inv.getArgument(1);
        return employees.stream()
            .filter(e -> userId.equals(e.getUserId()) && e.getStatus() == status)
            .toList();
      });
      when(employeeRepository.findByShopIdAndUserId(anyString(), anyString())).thenAnswer(inv -> {
        String shopId = inv.getArgument(0);
        String userId = inv.getArgument(1);
        return employees.stream()
            .filter(e -> shopId.equals(e.getShopId()) && userId.equals(e.getUserId()))
            .findFirst();
      });
      when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
        saves++;
        return inv.getArgument(0);
      });

      HrAuditService auditService = mock(HrAuditService.class);
      doAnswer(inv -> {
        audits.add(inv.getArgument(0));
        return null;
      }).when(auditService).record(any(AuditEntry.class));

      DevicePairingService pairingService = mock(DevicePairingService.class);
      when(pairingService.activePairing(anyString(), anyString())).thenReturn(Optional.empty());

      EmployeeService employeeService = new EmployeeService();
      ReflectionTestUtils.setField(employeeService, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(employeeService, "mongoTemplate", mock(MongoTemplate.class));
      ReflectionTestUtils.setField(employeeService, "employeeMapper", new EmployeeMapperImpl());
      ReflectionTestUtils.setField(employeeService, "validator", new EmployeeValidator());
      ReflectionTestUtils.setField(employeeService, "guard", mock(HrPermissionGuard.class));
      ReflectionTestUtils.setField(employeeService, "counterService",
          mock(HrCounterService.class));
      ReflectionTestUtils.setField(employeeService, "membershipService",
          mock(UserShopMembershipService.class));
      ReflectionTestUtils.setField(employeeService, "periodLockService",
          mock(PeriodLockService.class));
      ReflectionTestUtils.setField(employeeService, "auditService", auditService);
      ReflectionTestUtils.setField(employeeService, "pairingService", pairingService);
      ReflectionTestUtils.setField(employeeService, "notificationService",
          mock(HrNotificationService.class));

      WorkerPairingService workerPairingService = new WorkerPairingService();
      ReflectionTestUtils.setField(workerPairingService, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(workerPairingService, "workerAccounts", accounts);
      ReflectionTestUtils.setField(workerPairingService, "pairingService", pairingService);

      ShopNamePort shopNames = mock(ShopNamePort.class);
      when(shopNames.getShopName(anyString())).thenReturn("Sri Ganesh Stores");

      ReflectionTestUtils.setField(service, "workerAccounts", accounts);
      ReflectionTestUtils.setField(service, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(service, "punchRepository", mock(PunchRepository.class));
      ReflectionTestUtils.setField(service, "employeeService", employeeService);
      ReflectionTestUtils.setField(service, "pairingService", pairingService);
      ReflectionTestUtils.setField(service, "workerPairingService", workerPairingService);
      ReflectionTestUtils.setField(service, "stateService", mock(WorkerStateService.class));
      ReflectionTestUtils.setField(service, "notificationService",
          mock(HrNotificationService.class));
      ReflectionTestUtils.setField(service, "shopNamePort", shopNames);
      service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }
}
