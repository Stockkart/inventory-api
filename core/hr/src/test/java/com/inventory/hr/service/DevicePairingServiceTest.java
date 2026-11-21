package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PairingCodeRepository;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.ConsentResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.rest.dto.response.PairingCodeResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.KeyAttestation;
import com.inventory.hr.utils.PairingCodes;
import com.inventory.hr.utils.TestAttestationChains;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class DevicePairingServiceTest {

  private static final String EMPLOYEE = "emp-1";
  private static final String INSTALL = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final Instant NOW = Instant.parse("2025-04-03T09:12:00Z");

  @Mock private PairingCodeRepository codeRepository;
  @Mock private DevicePairingRepository pairingRepository;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private HrSettingsService settingsService;
  @Mock private HrAuditService auditService;
  @Mock private MetricsWrapper metrics;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private ShopNamePort shopNamePort;
  @Spy private ConsentVersions consentVersions = new ConsentVersions();
  @InjectMocks private DevicePairingService service;

  private Employee employee;

  @BeforeEach
  void setUp() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    employee = HrTestFixtures.employee(EMPLOYEE);
    lenient().when(employeeRepository.findByShopIdAndId(SHOP, EMPLOYEE))
        .thenReturn(Optional.of(employee));
    lenient().when(codeRepository.insert(any(PairingCode.class))).thenAnswer(i -> {
      PairingCode c = i.getArgument(0);
      c.setId("code-1");
      return c;
    });
    lenient().when(codeRepository.save(any(PairingCode.class))).thenAnswer(i -> i.getArgument(0));
    lenient().when(pairingRepository.insert(any(DevicePairing.class))).thenAnswer(i -> {
      DevicePairing p = i.getArgument(0);
      p.setId("pairing-1");
      return p;
    });
    switchOn(true);
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private void switchOn(boolean on) {
    HrSettings settings = new HrSettings();
    settings.setAttendanceCapture(new HrSettings.AttendanceCapture(false, on, true, 12.97, 77.59,
        150, OutsideAction.FLAG));
    lenient().when(settingsService.effectiveSettings(SHOP)).thenReturn(settings);
  }

  private static PairingCode unusedCode(String code) {
    PairingCode c = new PairingCode();
    c.setId("code-1");
    c.setShopId(SHOP);
    c.setEmployeeId(EMPLOYEE);
    c.setCodeHash(PairingCodes.hash(code));
    c.setStatus(PairingCodeStatus.UNUSED);
    c.setCreatedBy(USER);
    c.setCreatedAt(NOW.minusSeconds(60));
    c.setExpiresAt(NOW.minusSeconds(60).plus(Duration.ofMinutes(15)));
    c.setAttempts(0);
    return c;
  }

  private static PairingCode claimedCode(String token) {
    PairingCode c = unusedCode("ABCDEFGH");
    c.setStatus(PairingCodeStatus.CLAIMED);
    c.setClaimTokenHash(DevicePairingService.sha256Hex(token));
    c.setClaimedByInstallationId(INSTALL);
    c.setClaimedAt(NOW.minusSeconds(30));
    c.setClaimedOs(DeviceOs.ANDROID);
    c.setClaimedOsVersion("13");
    c.setClaimedPhoneModel("Redmi Note 11");
    c.setClaimedAppVersion("1.0.0");
    return c;
  }

  private static DevicePairing activePairing(String id, String employeeId, String model) {
    DevicePairing p = new DevicePairing();
    p.setId(id);
    p.setShopId(SHOP);
    p.setEmployeeId(employeeId);
    p.setStatus(PairingStatus.ACTIVE);
    p.setPhoneModel(model);
    p.setOs(DeviceOs.ANDROID);
    p.setPairedAt(NOW.minus(Duration.ofDays(10)));
    p.setPairedBy(USER);
    p.setConsentVersion(1);
    p.setInstallationId("old-install");
    return p;
  }

  private static String p256Spki() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
  }

  private static String p384Spki() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp384r1"));
    return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
  }

  private void codeLookup(String code, PairingCode doc) {
    when(codeRepository.findByCodeHash(PairingCodes.hash(code)))
        .thenReturn(Optional.ofNullable(doc));
  }

  private void tokenLookup(String token, PairingCode doc) {
    when(codeRepository.findByClaimTokenHash(DevicePairingService.sha256Hex(token)))
        .thenReturn(Optional.ofNullable(doc));
  }

  /** The conditional ACTIVE → UNPAIRED update, answered for one of the two displacement queries. */
  private void unpairAnswers(DevicePairing byEmployee, DevicePairing byInstallation) {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class))).thenAnswer(i -> {
          Query q = i.getArgument(0);
          DevicePairing hit =
              q.getQueryObject().containsKey("installationId") ? byInstallation : byEmployee;
          if (hit == null) {
            return null;
          }
          hit.setStatus(PairingStatus.UNPAIRED);
          Update u = i.getArgument(1);
          hit.setUnpairReason(UnpairReason.valueOf(
              u.getUpdateObject().get("$set", org.bson.Document.class)
                  .get("unpairReason").toString()));
          hit.setUnpairedAt(NOW);
          hit.setPurgeAt(NOW.plus(Duration.ofDays(90)));
          return hit;
        });
  }

  private BaseException claimFails(String code) {
    return assertThrows(BaseException.class,
        () -> service.claim(code, INSTALL, DeviceOs.ANDROID, "13", "Redmi Note 11", "1.0.0"));
  }

  private AuditEntry singleAudit(HrAuditAction action) {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(captor.capture());
    AuditEntry entry = captor.getValue();
    assertEquals(action, entry.action());
    return entry;
  }

  private void verifyEvent(String event, String reason) {
    verify(metrics).increment(eq(HrMetricsConstants.APP_PAIRINGS_TOTAL), eq(1.0),
        eq("module"), eq("hr"), eq("shop"), eq(SHOP), eq("event"), eq(event), eq("reason"),
        eq(reason));
  }

  // ---- createCode ----------------------------------------------------------------------------

  @Test
  void createCodeStoresTheHashOnlyAndExpiresIn15Minutes() {
    when(codeRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingCodeStatus.UNUSED)).thenReturn(List.of());

    PairingCodeResponse r = service.createCode(SHOP, EMPLOYEE, USER);

    assertTrue(PairingCodes.isValid(r.getCode()));
    assertEquals(PairingCodes.qrPayload(r.getCode()), r.getQrPayload());
    assertEquals(NOW.plus(Duration.ofMinutes(15)), r.getExpiresAt());
    ArgumentCaptor<PairingCode> captor = ArgumentCaptor.forClass(PairingCode.class);
    verify(codeRepository).insert(captor.capture());
    PairingCode stored = captor.getValue();
    assertEquals(PairingCodes.hash(r.getCode()), stored.getCodeHash());
    assertEquals(PairingCodeStatus.UNUSED, stored.getStatus());
    assertEquals(0, stored.getAttempts());
    assertEquals(USER, stored.getCreatedBy());
    assertFalse(stored.toString().contains(stored.getCodeHash()), "hash never logged");
    AuditEntry audit = singleAudit(HrAuditAction.PAIRING_CODE_CREATED);
    assertEquals(EMPLOYEE, audit.employeeId());
    assertEquals(USER, audit.actorUserId());
    assertFalse(String.valueOf(audit.after()).contains(r.getCode()), "code never audited");
    verifyEvent("created", "-");
  }

  @Test
  void createCodeCancelsThePreviousUnusedCode() {
    PairingCode previous = unusedCode("ABCDEFGH");
    when(codeRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingCodeStatus.UNUSED)).thenReturn(new ArrayList<>(List.of(previous)));

    service.createCode(SHOP, EMPLOYEE, USER);

    assertEquals(PairingCodeStatus.CANCELLED, previous.getStatus());
    verify(codeRepository).saveAll(List.of(previous));
  }

  @Test
  void createCodeRefusesAnExitedEmployee() {
    employee.setStatus(EmployeeStatus.EXITED);
    employee.setExitDate("2025-04-01");

    BaseException e = assertThrows(BaseException.class,
        () -> service.createCode(SHOP, EMPLOYEE, USER));

    assertEquals(ErrorCode.HR_PAIRING_EMPLOYEE_EXITED, e.getErrorCode());
    verify(codeRepository, never()).insert(any(PairingCode.class));
  }

  // ---- claim ---------------------------------------------------------------------------------

  @Test
  void claimReturnsNamesConsentAndATokenAndMarksTheCodeClaimed() {
    PairingCode doc = unusedCode("ABCDEFGH");
    codeLookup("ABCDEFGH", doc);

    ClaimPairingResponse r =
        service.claim("abcd-efgh", INSTALL, DeviceOs.ANDROID, "13", "Redmi Note 11", "1.0.0");

    assertEquals("Ravi Kumar", r.getEmployeeName());
    assertEquals("your shop", r.getShopName(), "no shop name known");
    assertEquals(consentVersions.latest(), r.getConsent());
    assertNotNull(r.getClaimToken());
    assertEquals(PairingCodeStatus.CLAIMED, doc.getStatus());
    assertEquals(DevicePairingService.sha256Hex(r.getClaimToken()), doc.getClaimTokenHash());
    assertNotEquals(r.getClaimToken(), doc.getClaimTokenHash(), "token stored hashed only");
    assertEquals(INSTALL, doc.getClaimedByInstallationId());
    assertEquals("Redmi Note 11", doc.getClaimedPhoneModel());
    assertEquals(DeviceOs.ANDROID, doc.getClaimedOs());
    assertEquals(1, doc.getAttempts(), "the claim itself counts");
    assertEquals(NOW, doc.getClaimedAt());
  }

  @Test
  void claimShowsTheShopNameFromThePort() {
    when(shopNamePort.getShopName(SHOP)).thenReturn("Kumar Stores");
    PairingCode doc = unusedCode("ABCDEFGH");
    codeLookup("ABCDEFGH", doc);

    ClaimPairingResponse r =
        service.claim("ABCDEFGH", INSTALL, DeviceOs.ANDROID, "13", "Redmi Note 11", "1.0.0");

    assertEquals("Kumar Stores", r.getShopName());
  }

  @Test
  void blankShopNameFallsBackToYourShop() {
    when(shopNamePort.getShopName(SHOP)).thenReturn("  ");

    assertEquals("your shop", service.shopName(SHOP));
  }

  @Test
  void claimWithACodeThatMatchesNothingIsWrong() {
    codeLookup("ABCDEFGH", null);

    assertEquals(ErrorCode.HR_PAIRING_CODE_WRONG, claimFails("ABCDEFGH").getErrorCode());
    verify(metrics).increment(eq(HrMetricsConstants.APP_PAIRINGS_TOTAL), eq(1.0),
        eq("module"), eq("hr"), eq("shop"), eq("unknown"), eq("event"), eq("failed"),
        eq("reason"), eq("code_wrong"));
  }

  @Test
  void claimWithABadlyFormedCodeIsWrongWithoutALookup() {
    assertEquals(ErrorCode.HR_PAIRING_CODE_WRONG, claimFails("ABC0").getErrorCode());
    verify(codeRepository, never()).findByCodeHash(anyString());
  }

  @Test
  void claimRefusesAnExpiredCode() {
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setExpiresAt(NOW);
    codeLookup("ABCDEFGH", doc);

    assertEquals(ErrorCode.HR_PAIRING_CODE_EXPIRED, claimFails("ABCDEFGH").getErrorCode());
    assertEquals(1, doc.getAttempts());
    verifyEvent("failed", "code_expired");
  }

  @Test
  void claimRefusesAUsedCode() {
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setStatus(PairingCodeStatus.USED);
    codeLookup("ABCDEFGH", doc);

    assertEquals(ErrorCode.HR_PAIRING_CODE_USED, claimFails("ABCDEFGH").getErrorCode());
    verifyEvent("failed", "code_used");
  }

  @Test
  void claimRefusesACodeClaimedByAnotherPhoneAsUsed() {
    PairingCode doc = claimedCode("tok");
    doc.setClaimedByInstallationId("another-install");
    codeLookup("ABCDEFGH", doc);

    assertEquals(ErrorCode.HR_PAIRING_CODE_USED, claimFails("ABCDEFGH").getErrorCode());
  }

  @Test
  void claimLetsTheSamePhoneClaimAgainAfterALostAnswer() {
    PairingCode doc = claimedCode("tok");
    codeLookup("ABCDEFGH", doc);

    ClaimPairingResponse r =
        service.claim("ABCDEFGH", INSTALL, DeviceOs.ANDROID, "13", "Redmi Note 11", "1.0.0");

    assertNotEquals(DevicePairingService.sha256Hex("tok"), doc.getClaimTokenHash(),
        "a new token replaces the lost one");
    assertEquals(DevicePairingService.sha256Hex(r.getClaimToken()), doc.getClaimTokenHash());
  }

  @Test
  void claimRefusesACancelledOrInvalidatedCode() {
    PairingCode cancelled = unusedCode("ABCDEFGH");
    cancelled.setStatus(PairingCodeStatus.CANCELLED);
    codeLookup("ABCDEFGH", cancelled);
    assertEquals(ErrorCode.HR_PAIRING_CODE_CANCELLED, claimFails("ABCDEFGH").getErrorCode());

    PairingCode invalidated = unusedCode("JKLMNPQR");
    invalidated.setStatus(PairingCodeStatus.INVALIDATED);
    codeLookup("JKLMNPQR", invalidated);
    assertEquals(ErrorCode.HR_PAIRING_CODE_CANCELLED, claimFails("JKLMNPQR").getErrorCode());
  }

  @Test
  void claimRefusesWhenTheBiometricAppIsOffForTheShop() {
    switchOn(false);
    codeLookup("ABCDEFGH", unusedCode("ABCDEFGH"));

    BaseException e = claimFails("ABCDEFGH");

    assertEquals(ErrorCode.HR_APP_OFF, e.getErrorCode());
    assertTrue(e.getMessage().endsWith("Ask your manager to mark your attendance."));
    verifyEvent("failed", "app_off");
  }

  @Test
  void claimRefusesAnExitedEmployeeButNotOneExitingLater() {
    employee.setStatus(EmployeeStatus.EXITED);
    employee.setExitDate("2025-04-03");
    codeLookup("ABCDEFGH", unusedCode("ABCDEFGH"));
    assertEquals(ErrorCode.HR_PAIRING_EMPLOYEE_EXITED, claimFails("ABCDEFGH").getErrorCode());
    verifyEvent("failed", "employee_exited");

    employee.setExitDate("2025-04-04");
    codeLookup("JKLMNPQR", unusedCode("JKLMNPQR"));
    assertNotNull(service.claim("JKLMNPQR", INSTALL, DeviceOs.ANDROID, "13", "M", "1"));
  }

  @Test
  void claimRefusalsAreCheckedInOrderExpiredBeforeSwitch() {
    switchOn(false);
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setExpiresAt(NOW.minusSeconds(1));
    codeLookup("ABCDEFGH", doc);

    assertEquals(ErrorCode.HR_PAIRING_CODE_EXPIRED, claimFails("ABCDEFGH").getErrorCode());
  }

  @Test
  void eleventhAttemptInvalidatesTheCodeEvenWhenItIsRight() {
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setAttempts(10);
    codeLookup("ABCDEFGH", doc);

    BaseException e = claimFails("ABCDEFGH");

    assertEquals(ErrorCode.HR_TOO_MANY_ATTEMPTS, e.getErrorCode());
    assertEquals(PairingCodeStatus.INVALIDATED, doc.getStatus());
    assertEquals(11, doc.getAttempts());
    verifyEvent("failed", "locked");
  }

  @Test
  void onceInvalidatedEveryLaterAttemptReadsAsCancelledNotTooMany() {
    // the state the 11th attempt left behind
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setStatus(PairingCodeStatus.INVALIDATED);
    doc.setAttempts(11);
    codeLookup("ABCDEFGH", doc);

    assertEquals(ErrorCode.HR_PAIRING_CODE_CANCELLED, claimFails("ABCDEFGH").getErrorCode());
    assertEquals(PairingCodeStatus.INVALIDATED, doc.getStatus());
    assertEquals(12, doc.getAttempts(), "still counted for the record");
    verifyEvent("failed", "code_cancelled");
    verify(metrics, never()).increment(eq(HrMetricsConstants.APP_PAIRINGS_TOTAL), eq(1.0),
        eq("module"), eq("hr"), eq("shop"), eq(SHOP), eq("event"), eq("failed"), eq("reason"),
        eq("locked"));

    // the phone that had claimed it legitimately gets the same answer on complete
    PairingCode claimed = claimedCode("tok");
    claimed.setStatus(PairingCodeStatus.INVALIDATED);
    claimed.setAttempts(11);
    tokenLookup("tok", claimed);
    BaseException e = assertThrows(BaseException.class,
        () -> service.complete("tok", INSTALL, "k", "k", 1));
    assertEquals(ErrorCode.HR_PAIRING_CODE_CANCELLED, e.getErrorCode());
    assertEquals(12, claimed.getAttempts());
  }

  @Test
  void aCancelledCodeOverTheAttemptLimitIsStillCancelledNotTooMany() {
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setStatus(PairingCodeStatus.CANCELLED);
    doc.setAttempts(10);
    codeLookup("ABCDEFGH", doc);

    assertEquals(ErrorCode.HR_PAIRING_CODE_CANCELLED, claimFails("ABCDEFGH").getErrorCode());
    assertEquals(PairingCodeStatus.CANCELLED, doc.getStatus(), "never re-labelled INVALIDATED");
  }

  @Test
  void tenthAttemptStillWorksAndNullAttemptsCountAsZero() {
    PairingCode doc = unusedCode("ABCDEFGH");
    doc.setAttempts(9);
    codeLookup("ABCDEFGH", doc);
    assertNotNull(service.claim("ABCDEFGH", INSTALL, DeviceOs.ANDROID, "13", "M", "1"));
    assertEquals(10, doc.getAttempts());

    PairingCode fresh = unusedCode("JKLMNPQR");
    fresh.setAttempts(null);
    codeLookup("JKLMNPQR", fresh);
    assertNotNull(service.claim("JKLMNPQR", INSTALL, DeviceOs.ANDROID, "13", "M", "1"));
    assertEquals(1, fresh.getAttempts());
  }

  // ---- complete ------------------------------------------------------------------------------

  @Test
  void completeInsertsAnActivePairingWithConsentAndDeletesTheCode() throws Exception {
    PairingCode doc = claimedCode("tok");
    tokenLookup("tok", doc);
    unpairAnswers(null, null);
    String punchKey = p256Spki();
    String deviceKey = p256Spki();

    PairingBootstrapResponse r = service.complete("tok", INSTALL, punchKey, deviceKey, 1);

    assertEquals("pairing-1", r.getPairingId());
    assertEquals(SHOP, r.getShopId());
    assertEquals(EMPLOYEE, r.getEmployeeId());
    assertEquals("Ravi Kumar", r.getEmployeeName());
    assertEquals(1, r.getConsentVersion());
    assertTrue(r.getLocation().isLocationCheck());
    assertEquals(12.97, r.getLocation().getShopLat());
    assertEquals(150, r.getLocation().getRadiusMetres());
    assertEquals(OutsideAction.FLAG, r.getLocation().getOutsideAction());

    ArgumentCaptor<DevicePairing> captor = ArgumentCaptor.forClass(DevicePairing.class);
    verify(pairingRepository).insert(captor.capture());
    DevicePairing p = captor.getValue();
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(punchKey, p.getPunchPublicKey());
    assertEquals(deviceKey, p.getDevicePublicKey());
    assertEquals(INSTALL, p.getInstallationId());
    assertEquals("Redmi Note 11", p.getPhoneModel());
    assertEquals(DeviceOs.ANDROID, p.getOs());
    assertEquals("13", p.getOsVersion());
    assertEquals("1.0.0", p.getAppVersion());
    assertEquals(NOW, p.getPairedAt());
    assertEquals(NOW, p.getConsentAt());
    assertEquals(1, p.getConsentVersion());
    assertEquals(List.of(new DevicePairing.Consent(1, NOW)), p.getConsents());
    assertEquals(USER, p.getPairedBy(), "the manager who created the code");
    assertNull(p.getUnpairedAt());
    assertNull(p.getPurgeAt());

    assertEquals(PairingCodeStatus.USED, doc.getStatus());
    assertEquals("pairing-1", doc.getPairingId());
    verify(codeRepository).delete(doc);

    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_PAIRED);
    assertEquals(EMPLOYEE, audit.employeeId());
    assertEquals(USER, audit.actorUserId());
    assertEquals("Redmi Note 11", audit.after().get("phoneModel"));
    assertNoSecretsIn(audit, punchKey, deviceKey);
    verifyEvent("completed", "-");
  }

  @Test
  void completeReplacesTheEmployeesOldPhone() throws Exception {
    PairingCode doc = claimedCode("tok");
    tokenLookup("tok", doc);
    DevicePairing old = activePairing("pairing-0", EMPLOYEE, "Galaxy M12");
    unpairAnswers(old, null);

    service.complete("tok", INSTALL, p256Spki(), p256Spki(), 1);

    assertEquals(PairingStatus.UNPAIRED, old.getStatus());
    assertEquals(UnpairReason.REPLACED, old.getUnpairReason());
    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_REPLACED);
    assertEquals("Galaxy M12", audit.before().get("phoneModel"));
    assertEquals("Redmi Note 11", audit.after().get("phoneModel"));
    assertEquals("REPLACED", audit.reason());
    verifyEvent("replaced", "-");
    verify(metrics, never()).increment(eq(HrMetricsConstants.APP_PAIRINGS_TOTAL), eq(1.0),
        eq("module"), eq("hr"), eq("shop"), eq(SHOP), eq("event"), eq("completed"), eq("reason"),
        eq("-"));
  }

  @Test
  void completeMovesTheInstallationOffItsPreviousEmployee() throws Exception {
    PairingCode doc = claimedCode("tok");
    tokenLookup("tok", doc);
    DevicePairing other = activePairing("pairing-9", "emp-2", "Redmi Note 11");
    other.setInstallationId(INSTALL);
    unpairAnswers(null, other);

    service.complete("tok", INSTALL, p256Spki(), p256Spki(), 1);

    assertEquals(UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE, other.getUnpairReason());
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, times(2)).record(captor.capture());
    AuditEntry unpaired = captor.getAllValues().get(0);
    assertEquals(HrAuditAction.DEVICE_UNPAIRED, unpaired.action());
    assertEquals("emp-2", unpaired.employeeId());
    assertEquals("PAIRED_TO_ANOTHER_EMPLOYEE", unpaired.reason());
    assertEquals(HrAuditAction.DEVICE_PAIRED, captor.getAllValues().get(1).action());
    verifyEvent("unpaired", "PAIRED_TO_ANOTHER_EMPLOYEE");
    verifyEvent("completed", "-");
  }

  // ---- key attestation (task 16.4) -----------------------------------------------------------

  @Test
  void completeWithoutAChainRecordsNoAttestationAndCountsNoAttestationEvent() throws Exception {
    tokenLookup("tok", claimedCode("tok"));
    tokenLookup("tok2", claimedCode("tok2"));
    unpairAnswers(null, null);

    service.complete("tok", INSTALL, p256Spki(), p256Spki(), 1, null);
    service.complete("tok2", INSTALL, p256Spki(), p256Spki(), 1, List.of());

    ArgumentCaptor<DevicePairing> captor = ArgumentCaptor.forClass(DevicePairing.class);
    verify(pairingRepository, times(2)).insert(captor.capture());
    for (DevicePairing p : captor.getAllValues()) {
      assertNull(p.getAttested());
      assertNull(p.getAttestationSecurityLevel());
      assertNull(p.getAttestationFailureReason());
    }
    verify(metrics, never()).increment(eq(HrMetricsConstants.APP_PAIRINGS_TOTAL), eq(1.0),
        eq("module"), eq("hr"), eq("shop"), eq(SHOP), eq("event"), eq("attestation"),
        eq("reason"), anyString());
    assertFalse(singleAuditAfter(HrAuditAction.DEVICE_PAIRED, 2).containsKey("attested"));
  }

  @Test
  void completeWithAGoodChainRecordsAttestedTrue() throws Exception {
    TestAttestationChains chains = new TestAttestationChains();
    service.setKeyAttestation(new KeyAttestation(List.of(chains.rootKey())));
    tokenLookup("tok", claimedCode("tok"));
    unpairAnswers(null, null);
    KeyPair punch = TestAttestationChains.p256();
    List<String> chain = chains.chain(punch.getPublic(), new TestAttestationChains.Options()
        .challenge("tok".getBytes(StandardCharsets.UTF_8))
        .level(TestAttestationChains.LEVEL_STRONGBOX));

    PairingBootstrapResponse r = service.complete("tok", INSTALL,
        TestAttestationChains.spki(punch.getPublic()), p256Spki(), 1, chain);

    assertEquals("pairing-1", r.getPairingId());
    DevicePairing p = insertedPairing();
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(Boolean.TRUE, p.getAttested());
    assertEquals(KeyAttestation.LEVEL_STRONGBOX, p.getAttestationSecurityLevel());
    assertNull(p.getAttestationFailureReason());
    verifyEvent("attestation", "ok");
    verifyEvent("completed", "-");
    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_PAIRED);
    assertEquals(Boolean.TRUE, audit.after().get("attested"));
    assertNoSecretsIn(audit, chain.get(0), TestAttestationChains.spki(punch.getPublic()));
  }

  @Test
  void completeWithABadChainRecordsAttestedFalseWithTheReasonAndStillPairs() throws Exception {
    TestAttestationChains chains = new TestAttestationChains();
    service.setKeyAttestation(new KeyAttestation(List.of(chains.rootKey())));
    tokenLookup("tok", claimedCode("tok"));
    unpairAnswers(null, null);
    KeyPair punch = TestAttestationChains.p256();
    // the chain attests a different key than the one being registered
    List<String> chain = chains.chain(TestAttestationChains.p256().getPublic(),
        new TestAttestationChains.Options().challenge("tok".getBytes(StandardCharsets.UTF_8)));

    PairingBootstrapResponse r = service.complete("tok", INSTALL,
        TestAttestationChains.spki(punch.getPublic()), p256Spki(), 1, chain);

    assertEquals("pairing-1", r.getPairingId(), "a failed attestation never refuses the pairing");
    DevicePairing p = insertedPairing();
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(Boolean.FALSE, p.getAttested());
    assertEquals(KeyAttestation.LEVEL_TRUSTED_ENVIRONMENT, p.getAttestationSecurityLevel());
    assertEquals(KeyAttestation.Reason.KEY_MISMATCH, p.getAttestationFailureReason());
    verifyEvent("attestation", "key_mismatch");
    verifyEvent("completed", "-");
    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_PAIRED);
    assertEquals(Boolean.FALSE, audit.after().get("attested"));
    assertEquals("key_mismatch", audit.after().get("attestationFailureReason"));
  }

  @Test
  void completeWithAnUnparseableChainRecordsAttestedFalseAndStillPairs() throws Exception {
    tokenLookup("tok", claimedCode("tok"));
    unpairAnswers(null, null);

    service.complete("tok", INSTALL, p256Spki(), p256Spki(), 1, List.of("not a certificate"));

    DevicePairing p = insertedPairing();
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(Boolean.FALSE, p.getAttested());
    assertEquals(KeyAttestation.Reason.CHAIN_UNPARSEABLE, p.getAttestationFailureReason());
    verifyEvent("attestation", "chain_unparseable");
  }

  @Test
  void pairingStateExposesAttested() {
    DevicePairing p = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    p.setAttested(true);
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE, PairingStatus.ACTIVE))
        .thenReturn(Optional.of(p));

    assertEquals(Boolean.TRUE, service.pairingState(SHOP, EMPLOYEE).getAttested());

    p.setAttested(null);
    assertNull(service.pairingState(SHOP, EMPLOYEE).getAttested(), "no chain sent → null");
  }

  private DevicePairing insertedPairing() {
    ArgumentCaptor<DevicePairing> captor = ArgumentCaptor.forClass(DevicePairing.class);
    verify(pairingRepository).insert(captor.capture());
    return captor.getValue();
  }

  /** The {@code after} map of the {@code n}-th audit row of {@code action} (all must be that action). */
  private Map<String, Object> singleAuditAfter(HrAuditAction action, int n) {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, times(n)).record(captor.capture());
    for (AuditEntry e : captor.getAllValues()) {
      assertEquals(action, e.action());
    }
    return captor.getAllValues().get(n - 1).after();
  }

  @Test
  void completeRefusesKeysThatAreNotP256() throws Exception {
    tokenLookup("tok", claimedCode("tok"));
    String good = p256Spki();

    ValidationException punch = assertThrows(ValidationException.class,
        () -> service.complete("tok", INSTALL, p384Spki(), good, 1));
    assertEquals(DevicePairingService.REASON_BAD_PUNCH_KEY, punch.getMessage());

    ValidationException device = assertThrows(ValidationException.class,
        () -> service.complete("tok", INSTALL, good, "not base64!", 1));
    assertEquals(DevicePairingService.REASON_BAD_DEVICE_KEY, device.getMessage());
    verify(pairingRepository, never()).insert(any(DevicePairing.class));
  }

  @Test
  void completeRefusesAnUnknownConsentVersion() throws Exception {
    tokenLookup("tok", claimedCode("tok"));
    String key = p256Spki();

    assertThrows(ValidationException.class, () -> service.complete("tok", INSTALL, key, key, 99));
    verify(pairingRepository, never()).insert(any(DevicePairing.class));
  }

  @Test
  void completeWithAnUnknownTokenIsExpired() throws Exception {
    tokenLookup("tok", null);
    String key = p256Spki();

    BaseException e = assertThrows(BaseException.class,
        () -> service.complete("tok", INSTALL, key, key, 1));
    assertEquals(ErrorCode.HR_PAIRING_CODE_EXPIRED, e.getErrorCode());
  }

  @Test
  void completeFromAnotherInstallationIsWrong() throws Exception {
    PairingCode doc = claimedCode("tok");
    tokenLookup("tok", doc);
    String key = p256Spki();

    BaseException e = assertThrows(BaseException.class,
        () -> service.complete("tok", "other-install", key, key, 1));
    assertEquals(ErrorCode.HR_PAIRING_CODE_WRONG, e.getErrorCode());
    assertEquals(1, doc.getAttempts(), "counted on the code");
  }

  @Test
  void completeWithAnAbandonedCodeIsCancelled() throws Exception {
    PairingCode doc = claimedCode("tok");
    doc.setStatus(PairingCodeStatus.INVALIDATED);
    tokenLookup("tok", doc);
    String key = p256Spki();

    BaseException e = assertThrows(BaseException.class,
        () -> service.complete("tok", INSTALL, key, key, 1));
    assertEquals(ErrorCode.HR_PAIRING_CODE_CANCELLED, e.getErrorCode());
  }

  // ---- abandon -------------------------------------------------------------------------------

  @Test
  void abandonInvalidatesTheCodeAndCountsAFailure() {
    PairingCode doc = claimedCode("tok");
    tokenLookup("tok", doc);

    service.abandon("tok");

    assertEquals(PairingCodeStatus.INVALIDATED, doc.getStatus());
    verify(codeRepository).save(doc);
    verifyEvent("failed", "abandoned");
  }

  @Test
  void abandonOfAnUnknownOrSpentTokenDoesNothing() {
    tokenLookup("gone", null);
    service.abandon("gone");
    service.abandon(null);

    PairingCode used = claimedCode("tok");
    used.setStatus(PairingCodeStatus.USED);
    tokenLookup("tok", used);
    service.abandon("tok");

    assertEquals(PairingCodeStatus.USED, used.getStatus());
    verify(codeRepository, never()).save(any(PairingCode.class));
    verifyNoInteractions(metrics);
  }

  // ---- unpair --------------------------------------------------------------------------------

  @Test
  void unpairIsOneConditionalUpdateWithAuditAndCounter() {
    DevicePairing p = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    unpairAnswers(p, null);

    Optional<DevicePairing> result = service.unpair("pairing-1", UnpairReason.MANUAL, USER);

    assertTrue(result.isPresent());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
        any(FindAndModifyOptions.class), eq(DevicePairing.class));
    org.bson.Document q = query.getValue().getQueryObject();
    assertEquals("pairing-1", q.get("_id"));
    assertEquals(PairingStatus.ACTIVE, q.get("status"), "only an ACTIVE row is unpaired");
    org.bson.Document set = update.getValue().getUpdateObject().get("$set", org.bson.Document.class);
    assertEquals(PairingStatus.UNPAIRED, set.get("status"));
    assertEquals(NOW, set.get("unpairedAt"));
    assertEquals(USER, set.get("unpairedBy"));
    assertEquals(UnpairReason.MANUAL, set.get("unpairReason"));
    assertEquals(NOW.plus(Duration.ofDays(90)), set.get("purgeAt"));
    assertTrue(update.getValue().getUpdateObject().get("$unset", org.bson.Document.class)
        .containsKey("scheduledUnpairAt"));

    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_UNPAIRED);
    assertEquals(USER, audit.actorUserId());
    assertEquals(EMPLOYEE, audit.employeeId());
    assertEquals("Redmi Note 11", audit.before().get("phoneModel"));
    assertEquals("MANUAL", audit.reason());
    assertNoSecretsIn(audit, "old-install");
    verifyEvent("unpaired", "MANUAL");
  }

  @Test
  void unpairOfAnAlreadyUnpairedOrUnknownPairingDoesNothing() {
    unpairAnswers(null, null);

    assertTrue(service.unpair("pairing-1", UnpairReason.WORKER, "self").isEmpty());
    assertTrue(service.unpair(null, UnpairReason.WORKER, "self").isEmpty());

    verify(auditService, never()).record(any());
    verifyNoInteractions(metrics);
  }

  // ---- consent -------------------------------------------------------------------------------

  @Test
  void recordConsentRaisesTheVersionAppendsTheAgreementAndAudits() {
    DevicePairing before = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    before.setConsentVersion(1);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class))).thenReturn(before);
    // a published second version the worker is agreeing to
    doReturn(Optional.of(new ConsentVersions.ConsentVersion(2,
        ConsentVersions.Kind.RECONSENT_REQUIRED, List.of(), List.of(), List.of())))
        .when(consentVersions).get(2);

    ConsentResponse response = service.recordConsent("pairing-1", 2);

    assertEquals(2, response.getAgreedVersion());
    assertEquals(NOW, response.getAt());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    ArgumentCaptor<FindAndModifyOptions> options =
        ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(), options.capture(),
        eq(DevicePairing.class));
    org.bson.Document q = query.getValue().getQueryObject();
    assertEquals("pairing-1", q.get("_id"));
    assertEquals(PairingStatus.ACTIVE, q.get("status"), "only an ACTIVE pairing records consent");
    org.bson.Document u = update.getValue().getUpdateObject();
    assertEquals(2, u.get("$max", org.bson.Document.class).get("consentVersion"),
        "the version never goes down");
    assertEquals(NOW, u.get("$set", org.bson.Document.class).get("consentAt"));
    DevicePairing.Consent pushed = (DevicePairing.Consent)
        u.get("$push", org.bson.Document.class).get("consents");
    assertEquals(2, pushed.getVersion());
    assertEquals(NOW, pushed.getAt());
    assertFalse(options.getValue().isReturnNew(), "the old row gives the before value");

    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_CONSENT_UPDATED);
    assertEquals(DevicePairingService.SELF, audit.actorUserId());
    assertEquals(EMPLOYEE, audit.employeeId());
    assertEquals("pairing-1", audit.entityId());
    assertEquals(1, audit.before().get("consentVersion"));
    assertEquals(2, audit.after().get("consentVersion"));
    assertEquals("Redmi Note 11", audit.after().get("phoneModel"));
    assertNoSecretsIn(audit, "old-install");
  }

  @Test
  void recordConsentToAnOlderVersionKeepsTheHigherOne() {
    DevicePairing before = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    before.setConsentVersion(3);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class))).thenReturn(before);

    ConsentResponse response = service.recordConsent("pairing-1", 1);

    assertEquals(3, response.getAgreedVersion());
    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_CONSENT_UPDATED);
    assertEquals(3, audit.after().get("consentVersion"));
    assertEquals(1, audit.after().get("agreedVersion"));
  }

  @Test
  void recordConsentToAnUnpublishedVersionIsA400AndWritesNothing() {
    assertThrows(ValidationException.class, () -> service.recordConsent("pairing-1", 99));
    assertThrows(ValidationException.class, () -> service.recordConsent("pairing-1", null));
    verifyNoInteractions(mongoTemplate, auditService);
  }

  @Test
  void recordConsentOnAnUnpairedOrUnknownPairingIsNotPaired() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class))).thenReturn(null);

    BaseException e = assertThrows(BaseException.class,
        () -> service.recordConsent("pairing-1", 1));
    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
    assertThrows(BaseException.class, () -> service.recordConsent(null, 1));
    verify(auditService, never()).record(any());
  }

  @Test
  void unpairActiveFindsTheEmployeesPairingFirst() {
    DevicePairing p = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingStatus.ACTIVE)).thenReturn(Optional.of(p));
    unpairAnswers(p, null);

    assertTrue(service.unpairActive(SHOP, EMPLOYEE, UnpairReason.EMPLOYEE_EXITED, "system")
        .isPresent());
    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_UNPAIRED);
    assertEquals("system", audit.actorUserId());
    assertEquals("EMPLOYEE_EXITED", audit.reason());
  }

  // ---- reads ---------------------------------------------------------------------------------

  @Test
  void pairingStateNotPairedWithAPendingCode() {
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingStatus.ACTIVE)).thenReturn(Optional.empty());
    PairingCode expired = unusedCode("ABCDEFGH");
    expired.setExpiresAt(NOW.minusSeconds(1));
    PairingCode live = unusedCode("JKLMNPQR");
    when(codeRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingCodeStatus.UNUSED)).thenReturn(List.of(expired, live));
    when(codeRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingCodeStatus.CLAIMED)).thenReturn(List.of());

    PairingStateResponse r = service.pairingState(SHOP, EMPLOYEE);

    assertEquals(PairingStateResponse.State.NOT_PAIRED, r.getState());
    assertNull(r.getPhoneModel());
    assertEquals(live.getExpiresAt(), r.getPendingCode().getExpiresAt());
  }

  @Test
  void pairingStatePairedShowsPhoneAndLastPunch() {
    DevicePairing p = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    p.setLastPunchAt(NOW.minusSeconds(3600));
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingStatus.ACTIVE)).thenReturn(Optional.of(p));
    when(codeRepository.findByShopIdAndEmployeeIdAndStatus(eq(SHOP), eq(EMPLOYEE), any()))
        .thenReturn(List.of());

    PairingStateResponse r = service.pairingState(SHOP, EMPLOYEE);

    assertEquals(PairingStateResponse.State.PAIRED, r.getState());
    assertEquals("Redmi Note 11", r.getPhoneModel());
    assertEquals(DeviceOs.ANDROID, r.getOs());
    assertEquals(p.getPairedAt(), r.getPairedAt());
    assertEquals(NOW.minusSeconds(3600), r.getLastPunchAt());
    assertEquals(USER, r.getPairedBy());
    assertEquals(1, r.getConsentVersion());
    assertNull(r.getPendingCode());
  }

  @Test
  void activePairingsByEmployeeIsOneReadKeyedByEmployee() {
    DevicePairing a = activePairing("p-a", "emp-a", "A");
    DevicePairing b = activePairing("p-b", "emp-b", "B");
    when(pairingRepository.findByShopIdAndEmployeeIdInAndStatus(SHOP,
        List.of("emp-a", "emp-b", "emp-c"), PairingStatus.ACTIVE)).thenReturn(List.of(a, b));

    Map<String, DevicePairing> out =
        service.activePairingsByEmployee(SHOP, List.of("emp-a", "emp-b", "emp-c"));

    assertEquals(2, out.size());
    assertEquals(a, out.get("emp-a"));
    assertEquals(b, out.get("emp-b"));
    assertTrue(service.activePairingsByEmployee(SHOP, List.of()).isEmpty());
    verify(pairingRepository, times(1)).findByShopIdAndEmployeeIdInAndStatus(any(), any(), any());
  }

  @Test
  void isExitedFollowsTheExitDate() {
    Employee e = HrTestFixtures.employee(EMPLOYEE);
    java.time.LocalDate today = java.time.LocalDate.of(2025, 4, 3);
    assertFalse(DevicePairingService.isExited(e, today), "ACTIVE");
    e.setStatus(EmployeeStatus.EXITED);
    e.setExitDate("2025-04-04");
    assertFalse(DevicePairingService.isExited(e, today), "exit tomorrow: still employed");
    e.setExitDate("2025-04-03");
    assertTrue(DevicePairingService.isExited(e, today), "exit today: unpaired at 00:00");
    e.setExitDate(null);
    assertTrue(DevicePairingService.isExited(e, today), "EXITED without a date");
  }

  // ---- exit schedule (R3.4–R3.6) -------------------------------------------------------------

  private org.bson.Document scheduleUpdate() {
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(DevicePairing.class));
    org.bson.Document q = query.getValue().getQueryObject();
    assertEquals(SHOP, q.get("shopId"));
    assertEquals(EMPLOYEE, q.get("employeeId"));
    assertEquals(PairingStatus.ACTIVE, q.get("status"), "only the active pairing is touched");
    return update.getValue().getUpdateObject();
  }

  @Test
  void aFutureExitDateIsScheduledAtMidnightKolkataAndNothingIsUnpairedYet() {
    // NOW is 2025-04-03 14:42 IST; exit on the 10th
    Optional<DevicePairing> now = service.scheduleExitUnpair(SHOP, EMPLOYEE,
        java.time.LocalDate.of(2025, 4, 10));

    assertTrue(now.isEmpty());
    org.bson.Document set = scheduleUpdate().get("$set", org.bson.Document.class);
    assertEquals(Instant.parse("2025-04-09T18:30:00Z"), set.get("scheduledUnpairAt"),
        "00:00 Asia/Kolkata on the exit date");
    verify(pairingRepository, never()).findByShopIdAndEmployeeIdAndStatus(any(), any(), any());
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class));
    verifyNoInteractions(auditService);
  }

  @Test
  void anExitDateTomorrowIsStillOnlyScheduled() {
    service.scheduleExitUnpair(SHOP, EMPLOYEE, java.time.LocalDate.of(2025, 4, 4));

    assertEquals(Instant.parse("2025-04-03T18:30:00Z"),
        scheduleUpdate().get("$set", org.bson.Document.class).get("scheduledUnpairAt"));
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class));
  }

  @Test
  void anExitDateTodayUnpairsAtOnceAsTheSystem() {
    DevicePairing p = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingStatus.ACTIVE)).thenReturn(Optional.of(p));
    unpairAnswers(p, null);

    Optional<DevicePairing> now = service.scheduleExitUnpair(SHOP, EMPLOYEE,
        java.time.LocalDate.of(2025, 4, 3));

    assertTrue(now.isPresent());
    assertEquals(UnpairReason.EMPLOYEE_EXITED, p.getUnpairReason());
    // the schedule is written first, so a failed unpair is finished by PairingExitJob
    assertEquals(Instant.parse("2025-04-02T18:30:00Z"),
        scheduleUpdate().get("$set", org.bson.Document.class).get("scheduledUnpairAt"));
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(any(Query.class), update.capture(),
        any(FindAndModifyOptions.class), eq(DevicePairing.class));
    assertEquals("system",
        update.getValue().getUpdateObject().get("$set", org.bson.Document.class).get("unpairedBy"));
    AuditEntry audit = singleAudit(HrAuditAction.DEVICE_UNPAIRED);
    assertEquals("system", audit.actorUserId());
    assertEquals("EMPLOYEE_EXITED", audit.reason());
    assertEquals("Redmi Note 11", audit.before().get("phoneModel"));
    verifyEvent("unpaired", "EMPLOYEE_EXITED");
  }

  @Test
  void anExitDateInThePastUnpairsAtOnce() {
    DevicePairing p = activePairing("pairing-1", EMPLOYEE, "Redmi Note 11");
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingStatus.ACTIVE)).thenReturn(Optional.of(p));
    unpairAnswers(p, null);

    assertTrue(service.scheduleExitUnpair(SHOP, EMPLOYEE, java.time.LocalDate.of(2025, 3, 1))
        .isPresent());
    assertEquals(PairingStatus.UNPAIRED, p.getStatus());
  }

  @Test
  void anExitWithoutAPairedPhoneWritesNothing() {
    when(pairingRepository.findByShopIdAndEmployeeIdAndStatus(SHOP, EMPLOYEE,
        PairingStatus.ACTIVE)).thenReturn(Optional.empty());

    assertTrue(service.scheduleExitUnpair(SHOP, EMPLOYEE, java.time.LocalDate.of(2025, 4, 1))
        .isEmpty());

    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(DevicePairing.class));
    verifyNoInteractions(auditService, metrics);
  }

  @Test
  void clearingTheScheduleUnsetsItOnTheActivePairingOnly() {
    service.clearExitUnpair(SHOP, EMPLOYEE);

    org.bson.Document update = scheduleUpdate();
    assertNull(update.get("$set"));
    assertTrue(update.get("$unset", org.bson.Document.class).containsKey("scheduledUnpairAt"));
    verifyNoInteractions(auditService, metrics);
  }

  @Test
  void anExitedEmployeeWithoutADateClearsTheSchedule() {
    assertTrue(service.scheduleExitUnpair(SHOP, EMPLOYEE, null).isEmpty());

    assertTrue(scheduleUpdate().get("$unset", org.bson.Document.class)
        .containsKey("scheduledUnpairAt"));
  }

  // ---- privacy -------------------------------------------------------------------------------

  private static void assertNoSecretsIn(AuditEntry entry, String... secrets) {
    String text = String.valueOf(entry.before()) + String.valueOf(entry.after())
        + String.valueOf(entry.reason());
    for (String secret : secrets) {
      assertFalse(text.contains(secret), "audit row carries " + secret);
    }
    assertFalse(text.contains(INSTALL), "audit row carries the installation id");
    assertFalse(text.contains("12.97") || text.contains("77.59"), "audit row carries coordinates");
  }
}
