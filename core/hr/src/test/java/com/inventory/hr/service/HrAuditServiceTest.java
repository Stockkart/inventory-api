// Feature: hr-module, Property 7: Without VIEW_SALARIES no response, log line or audit event carries pay amounts or full bank/UPI details
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrAuditEventRepository;
import com.inventory.hr.domain.repository.PairingCodeRepository;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Audit rows never carry bank account, IFSC, UPI or salary amounts (R14.7, design "Data model"),
 * only change flags. The {@code @Property} runs under {@code mvn test} because the class name ends
 * in {@code Test} (AGENTS.md T5).
 *
 * <p><b>Validates: Requirements 4.5, 4.13</b>
 */
@ExtendWith(MockitoExtension.class)
class HrAuditServiceTest {

  @Mock private HrAuditEventRepository repository;
  @Mock private MetricsWrapper metrics;
  @InjectMocks private HrAuditService service;

  private static AuditEntry employeeChange(Employee before, Employee after) {
    return new AuditEntry(SHOP, USER, HrAuditAction.EMPLOYEE_CHANGED, "EMPLOYEE", "e1", "e1",
        null, null, before != null ? EmployeeService.snapshot(before) : null,
        EmployeeService.snapshot(after), null);
  }

  @Test
  void bankAndSalaryChangesBecomeFlags() {
    Employee before = employee("e1");
    Employee after = employee("e1");
    after.setBankAccountNumber("999988887777");
    after.getSalaryRevisions().add(new Employee.SalaryRevision("2025-04", PayType.MONTHLY,
        new BigDecimal("16000"), Instant.EPOCH, USER));

    HrAuditEvent event = HrAuditService.toEvent(employeeChange(before, after), Instant.EPOCH);

    assertEquals(true, event.getAfter().get(HrAuditService.PAYMENT_DETAILS_CHANGED));
    assertEquals(true, event.getAfter().get(HrAuditService.SALARY_CHANGED));
    assertNoPii(event, before, after);
  }

  @Test
  void anUnchangedPayLeavesNoFlag() {
    Employee before = employee("e1");
    Employee after = employee("e1");
    after.setName("Ravi K");

    HrAuditEvent event = HrAuditService.toEvent(employeeChange(before, after), Instant.EPOCH);

    assertFalse(event.getAfter().containsKey(HrAuditService.PAYMENT_DETAILS_CHANGED));
    assertFalse(event.getAfter().containsKey(HrAuditService.SALARY_CHANGED));
    assertEquals("Ravi K", event.getAfter().get("name"));
  }

  @Test
  void nestedMapsAreStrippedToo() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("upiId", "ravi@okaxis");
    nested.put("label", "kept");
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("line", nested);

    Map<String, Object> stripped = HrAuditService.strip(after);

    assertEquals(Map.of("label", "kept"), stripped.get("line"));
  }

  @Test
  void theRowIsInsertedNeverUpdated() {
    service.record(employeeChange(null, employee("e1")));

    ArgumentCaptor<HrAuditEvent> saved = ArgumentCaptor.forClass(HrAuditEvent.class);
    verify(repository).insert(saved.capture());
    assertNull(saved.getValue().getBefore());
    assertEquals(true, saved.getValue().getAfter().get(HrAuditService.PAYMENT_DETAILS_CHANGED));
  }

  @Test
  void aFailedWriteIsCountedAndDoesNotFailTheAction() {
    when(repository.insert(any(HrAuditEvent.class))).thenThrow(new RuntimeException("down"));

    assertDoesNotThrow(() -> service.record(employeeChange(null, employee("e1"))));
    verify(metrics).increment(eq(HrMetricsConstants.AUDIT_FAILURES_TOTAL), anyDouble(),
        eq("module"), eq("hr"), eq("action"), eq("EMPLOYEE_CHANGED"));
  }

  @Property(tries = 100)
  void noAuditRowEverHoldsPaymentDetailsOrAmounts(
      @ForAll("employees") Employee before, @ForAll("employees") Employee after) {
    HrAuditEvent event = HrAuditService.toEvent(employeeChange(before, after), Instant.EPOCH);

    assertNoPii(event, before, after);
  }

  // ---- Attendance app actions (R2.17, R3.1, R3.6, R3.9, R9.1; task 7.7) ----------------------

  private static final String INSTALL_1 = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final String INSTALL_2 = "0b7a2b5e-3d7a-4a3e-9d2f-6c1e8f0a9b3c";

  private static String p256Spki() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
  }

  /**
   * The real {@link DevicePairingService} writing through the real {@link HrAuditService} under
   * test, over in-memory code and pairing stores. {@code active} is the employee's current
   * pairing, as the conditional updates of the service would find it.
   */
  private DevicePairingService pairingServiceWritingAuditRows(AtomicReference<DevicePairing> active)
      throws Exception {
    Map<String, PairingCode> codesByHash = new LinkedHashMap<>();
    PairingCodeRepository codes = mock(PairingCodeRepository.class);
    when(codes.insert(any(PairingCode.class))).thenAnswer(i -> {
      PairingCode c = i.getArgument(0);
      c.setId("code-" + (codesByHash.size() + 1));
      codesByHash.put(c.getCodeHash(), c);
      return c;
    });
    when(codes.save(any(PairingCode.class))).thenAnswer(i -> i.getArgument(0));
    when(codes.findByCodeHash(any())).thenAnswer(i ->
        Optional.ofNullable(codesByHash.get(i.<String>getArgument(0))));
    when(codes.findByClaimTokenHash(any())).thenAnswer(i -> codesByHash.values().stream()
        .filter(c -> i.getArgument(0).equals(c.getClaimTokenHash())).findFirst());
    doAnswer(i -> codesByHash.values().remove(i.<PairingCode>getArgument(0)))
        .when(codes).delete(any(PairingCode.class));

    AtomicInteger pairingIds = new AtomicInteger();
    DevicePairingRepository pairings = mock(DevicePairingRepository.class);
    when(pairings.insert(any(DevicePairing.class))).thenAnswer(i -> {
      DevicePairing p = i.getArgument(0);
      p.setId("pairing-" + pairingIds.incrementAndGet());
      active.set(p);
      return p;
    });

    // the three conditional updates: by employee (replace), by installation (move), by id
    MongoTemplate template = mock(MongoTemplate.class);
    when(template.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class))).thenAnswer(i -> {
          org.bson.Document q = i.<Query>getArgument(0).getQueryObject();
          DevicePairing current = active.get();
          if (current == null || !PairingStatus.ACTIVE.equals(current.getStatus())) {
            return null;
          }
          if (q.containsKey("installationId")) {
            return null; // every phone here is a fresh installation
          }
          if (q.containsKey("_id") && !current.getId().equals(q.get("_id"))) {
            return null;
          }
          org.bson.Document set = i.<Update>getArgument(1).getUpdateObject()
              .get("$set", org.bson.Document.class);
          if (set != null && set.containsKey("unpairReason")) {
            current.setStatus(PairingStatus.UNPAIRED);
            current.setUnpairReason(UnpairReason.valueOf(set.get("unpairReason").toString()));
          }
          return current;
        });

    EmployeeRepository employees = mock(EmployeeRepository.class);
    when(employees.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    HrSettingsService settings = mock(HrSettingsService.class);
    HrSettings s = new HrSettings();
    s.setAttendanceCapture(new HrSettings.AttendanceCapture(false, true, true, 12.971837,
        77.594711, 100, OutsideAction.FLAG));
    when(settings.effectiveSettings(SHOP)).thenReturn(s);

    DevicePairingService pairingService = new DevicePairingService();
    ReflectionTestUtils.setField(pairingService, "codeRepository", codes);
    ReflectionTestUtils.setField(pairingService, "pairingRepository", pairings);
    ReflectionTestUtils.setField(pairingService, "employeeRepository", employees);
    ReflectionTestUtils.setField(pairingService, "settingsService", settings);
    ReflectionTestUtils.setField(pairingService, "consentVersions", new ConsentVersions());
    ReflectionTestUtils.setField(pairingService, "auditService", service);
    ReflectionTestUtils.setField(pairingService, "metrics", metrics);
    ReflectionTestUtils.setField(pairingService, "mongoTemplate", template);
    ReflectionTestUtils.setField(pairingService, "shopNamePort", mock(ShopNamePort.class));
    pairingService.setClock(Clock.fixed(Instant.parse("2025-04-03T09:12:00Z"), ZoneOffset.UTC));
    return pairingService;
  }

  private static HrAuditEvent only(List<HrAuditEvent> rows, HrAuditAction action) {
    List<HrAuditEvent> hits = rows.stream().filter(r -> r.getAction() == action).toList();
    assertEquals(1, hits.size(), "one " + action + " row");
    return hits.get(0);
  }

  @Test
  void theAttendanceAppRowsCarryPhoneModelActorAndReasonAndNeverAKeyOrInstallationId()
      throws Exception {
    AtomicReference<DevicePairing> active = new AtomicReference<>();
    DevicePairingService pairings = pairingServiceWritingAuditRows(active);
    String punchKey1 = p256Spki();
    String deviceKey1 = p256Spki();
    String punchKey2 = p256Spki();
    String deviceKey2 = p256Spki();

    // the manager pairs the first phone, then replaces it; the worker re-consents; the manager
    // unpairs: every one of the five new actions, as the service writes them
    String code1 = pairings.createCode(SHOP, "e1", USER).getCode();
    String token1 = pairings.claim(code1, INSTALL_1, DeviceOs.ANDROID, "14", "Redmi Note 11",
        "1.0.0").getClaimToken();
    pairings.complete(token1, INSTALL_1, punchKey1, deviceKey1, 1);
    String code2 = pairings.createCode(SHOP, "e1", USER).getCode();
    String token2 = pairings.claim(code2, INSTALL_2, DeviceOs.ANDROID, "15", "Pixel 7a", "1.0.0")
        .getClaimToken();
    pairings.complete(token2, INSTALL_2, punchKey2, deviceKey2, 1);
    pairings.recordConsent(active.get().getId(), 1);
    pairings.unpair(active.get().getId(), UnpairReason.MANUAL, USER);

    ArgumentCaptor<HrAuditEvent> inserted = ArgumentCaptor.forClass(HrAuditEvent.class);
    verify(repository, times(6)).insert(inserted.capture());
    List<HrAuditEvent> rows = inserted.getAllValues();
    assertEquals(Set.of(HrAuditAction.PAIRING_CODE_CREATED, HrAuditAction.DEVICE_PAIRED,
            HrAuditAction.DEVICE_REPLACED, HrAuditAction.DEVICE_CONSENT_UPDATED,
            HrAuditAction.DEVICE_UNPAIRED),
        rows.stream().map(HrAuditEvent::getAction).collect(Collectors.toSet()),
        "all five new actions were written");

    // who, which employee, which record: on every row
    for (HrAuditEvent row : rows) {
      assertEquals(SHOP, row.getShopId());
      assertEquals("e1", row.getEmployeeId(), row.getAction() + " names the employee");
      assertTrue(row.getActorUserId() != null && !row.getActorUserId().isBlank(),
          row.getAction() + " names the actor");
      assertTrue(row.getEntityId() != null && !row.getEntityId().isBlank());
    }
    HrAuditEvent paired = only(rows, HrAuditAction.DEVICE_PAIRED);
    assertEquals(USER, paired.getActorUserId());
    assertEquals("Redmi Note 11", paired.getAfter().get("phoneModel"));
    assertEquals(USER, paired.getAfter().get("pairedBy"));
    assertNull(paired.getReason());
    HrAuditEvent replaced = only(rows, HrAuditAction.DEVICE_REPLACED);
    assertEquals(USER, replaced.getActorUserId());
    assertEquals("Redmi Note 11", replaced.getBefore().get("phoneModel"));
    assertEquals("Pixel 7a", replaced.getAfter().get("phoneModel"));
    assertEquals(UnpairReason.REPLACED.name(), replaced.getReason());
    HrAuditEvent consented = only(rows, HrAuditAction.DEVICE_CONSENT_UPDATED);
    assertEquals(DevicePairingService.SELF, consented.getActorUserId(), "the worker agrees");
    assertEquals("Pixel 7a", consented.getAfter().get("phoneModel"));
    assertEquals(1, consented.getAfter().get("agreedVersion"));
    HrAuditEvent unpaired = only(rows, HrAuditAction.DEVICE_UNPAIRED);
    assertEquals(USER, unpaired.getActorUserId());
    assertEquals("Pixel 7a", unpaired.getBefore().get("phoneModel"));
    assertEquals(UnpairReason.MANUAL.name(), unpaired.getReason());
    assertEquals(2, rows.stream().filter(r -> r.getAction() == HrAuditAction.PAIRING_CODE_CREATED)
        .filter(r -> USER.equals(r.getActorUserId())).count());

    // never a key, installation id, claim token, clear-text code or coordinate (R9.1, R9.2, R9.11)
    String stored = rows.stream().map(r -> String.valueOf(r.getBefore()) + r.getAfter()
        + r.getReason() + r.getEntityId() + r.getEntityType()).collect(Collectors.joining("\n"));
    for (String secret : List.of(punchKey1, deviceKey1, punchKey2, deviceKey2, INSTALL_1,
        INSTALL_2, token1, token2, code1, code2)) {
      assertFalse(stored.contains(secret), "audit rows carry " + secret);
    }
    assertFalse(stored.contains("12.97") || stored.contains("77.59"), "coordinates in audit");
    assertFalse(stored.toLowerCase().contains("publickey"), "no key field at all");
  }

  @Provide
  Arbitrary<Employee> employees() {
    Arbitrary<String> account = Arbitraries.strings().numeric().ofMinLength(6).ofMaxLength(18);
    Arbitrary<String> upi = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
        .map(s -> s + "@okaxis");
    Arbitrary<BigDecimal> amount = Arbitraries.bigDecimals()
        .between(new BigDecimal("1000.01"), new BigDecimal("999999.99")).ofScale(2);
    return Combinators.combine(account, upi, amount).as((a, u, m) -> {
      Employee e = employee("e1");
      e.setBankAccountNumber(a);
      e.setUpiId(u);
      e.getSalaryRevisions().get(0).setAmount(m);
      return e;
    });
  }

  private static void assertNoPii(HrAuditEvent event, Employee... employees) {
    String stored = String.valueOf(event.getBefore()) + event.getAfter() + event.getReason();
    for (Employee e : employees) {
      assertFalse(stored.contains(e.getBankAccountNumber()), "account number in audit");
      assertFalse(stored.contains(e.getIfsc()), "IFSC in audit");
      assertFalse(stored.contains(e.getUpiId()), "UPI id in audit");
      e.getSalaryRevisions().forEach(r ->
          assertFalse(stored.contains(r.getAmount().toPlainString()), "salary amount in audit"));
    }
    assertTrue(event.getAfter() == null || !event.getAfter().containsKey("salaryRevisions"));
  }
}
