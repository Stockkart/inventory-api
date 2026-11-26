package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.rest.dto.response.AppPunchResponse.Outcome;
import com.inventory.hr.rest.dto.response.AppStatusResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPunchResult;
import com.inventory.hr.validation.AppPunchValidator;
import com.inventory.metrics.MetricsWrapper;
import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link AppPunchService} against an in-memory punch store, with the real {@link
 * SelfCheckInService} refusal checks and the real {@link AppPunchValidator}. The clock is pinned
 * to 09:12 IST on Tuesday 8 April 2025.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppPunchServiceTest {

  private static final String SHOP_ID = "5f1a2b3c4d5e6f7a8b9c0d1e";
  private static final String EMP_ID = "6a1b2c3d4e5f6a7b8c9d0e1f";
  private static final String PAIRING_ID = "7b2c3d4e5f6a7b8c9d0e1f2a";
  private static final String PHONE = "Redmi Note 11";
  /** 09:12:00 IST on 2025-04-08. */
  private static final Instant NOW = Instant.parse("2025-04-08T03:42:00Z");
  private static final String TODAY = "2025-04-08";

  @Mock private HrSettingsService settingsService;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private PunchDayService punchDays;
  @Mock private PunchRepository punchRepository;
  @Mock private DevicePairingService pairingService;
  @Mock private AppRateLimiter rateLimiter;
  @Mock private ConsentVersions consentVersions;
  @Mock private MetricsWrapper metrics;
  @Mock private AppPunchHealthService health;
  // the real refusal checks (R4.3) over mocked leave / lock / permission reads
  @Mock private AttendanceService attendanceService;
  @Mock private PeriodLockService periodLockService;
  @Mock private HrAccessService hrAccessService;
  @Mock private SelfServiceResolver selfServiceResolver;
  @Spy private AppPunchValidator validator = new AppPunchValidator();

  @InjectMocks private SelfCheckInService selfCheckIn;
  @InjectMocks private AppPunchService service;

  private HrSettings settings;
  private Employee employee;
  private DevicePairing pairing;
  private KeyPair punchKey;
  private final List<Punch> stored = new ArrayList<>();
  private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @BeforeEach
  void setUp() throws Exception {
    set(service, "selfCheckIn", selfCheckIn);
    service.setClock(clock);

    settings = HrSettingsService.withDefaults(null, SHOP_ID);
    settings.getAttendanceCapture().setBiometricApp(true);
    when(settingsService.effectiveSettings(SHOP_ID)).thenReturn(settings);

    employee = new Employee();
    employee.setId(EMP_ID);
    employee.setShopId(SHOP_ID);
    employee.setName("Ravi Kumar");
    employee.setJoinDate("2020-01-01");
    employee.setStatus(EmployeeStatus.ACTIVE);
    when(employeeRepository.findByShopIdAndId(SHOP_ID, EMP_ID)).thenReturn(Optional.of(employee));

    punchKey = keyPair();
    pairing = new DevicePairing();
    pairing.setId(PAIRING_ID);
    pairing.setShopId(SHOP_ID);
    pairing.setEmployeeId(EMP_ID);
    pairing.setStatus(PairingStatus.ACTIVE);
    pairing.setPunchPublicKey(spki(punchKey));
    pairing.setPhoneModel(PHONE);
    pairing.setConsentVersion(1);

    when(rateLimiter.tryAcquirePunch(PAIRING_ID)).thenReturn(true);
    when(attendanceService.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
        anyLong())).thenReturn(List.of());
    when(attendanceService.holidayDates(anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(Set.of());
    when(periodLockService.lockedPeriods(anyString(), anyCollection())).thenReturn(Set.of());
    when(pairingService.shopName(SHOP_ID)).thenReturn("Kumar Stores");
    when(consentVersions.latest()).thenReturn(new ConsentVersions.ConsentVersion(1,
        ConsentVersions.Kind.RECONSENT_REQUIRED, List.of(), List.of(), List.of()));
    when(consentVersions.reconsentRequired(1)).thenReturn(false);

    // in-memory punch store
    when(punchDays.punchesOf(eq(SHOP_ID), eq(EMP_ID), anyString())).thenAnswer(i -> {
      String date = i.getArgument(2);
      return stored.stream().filter(p -> date.equals(p.getLocalDate())).toList();
    });
    when(punchRepository.findByShopIdAndDedupeKey(eq(SHOP_ID), anyString())).thenAnswer(i -> {
      String key = i.getArgument(1);
      return stored.stream().filter(p -> key.equals(p.getDedupeKey())).findFirst();
    });
    when(punchRepository.insert(any(Punch.class))).thenAnswer(i -> {
      Punch p = i.getArgument(0);
      p.setId("p" + (stored.size() + 1));
      stored.add(p);
      return p;
    });
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static KeyPair keyPair() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  private static String spki(KeyPair kp) {
    return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
  }

  private static String sign(KeyPair kp, byte[] payload) throws GeneralSecurityException {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(kp.getPrivate());
    s.update(payload);
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static String newPunchId() {
    return UUID.randomUUID().toString().toLowerCase();
  }

  private static PunchFields online(String punchId, Instant phoneTime) {
    return new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID, punchId, phoneTime, false, false, null,
        null, false);
  }

  private static PunchFields offline(String punchId, Instant phoneTime) {
    return new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID, punchId, phoneTime, true, false, null,
        null, false);
  }

  private static PunchFields located(String punchId, Integer distance, Integer accuracy,
      boolean mock) {
    return new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID, punchId, NOW, false, true, distance,
        accuracy, mock);
  }

  /** A request whose signature was made by {@code key} over exactly these fields. */
  private static AppPunchRequest signed(PunchFields f, KeyPair key) throws Exception {
    AppPunchRequest r = new AppPunchRequest();
    r.setPairingId(f.pairingId());
    r.setShopId(f.shopId());
    r.setEmployeeId(f.employeeId());
    r.setPunchId(f.punchId());
    r.setPhoneTime(f.phoneTime().toString());
    r.setOffline(f.offline());
    r.setLocationChecked(f.locationChecked());
    r.setDistanceMetres(f.distanceMetres());
    r.setAccuracyMetres(f.accuracyMetres());
    r.setMock(f.mock());
    r.setSignature(sign(key, AppSignatures.punchPayload(f)));
    return r;
  }

  private AppPunchRequest signed(PunchFields f) throws Exception {
    return signed(f, punchKey);
  }

  private void locationCheck(OutsideAction action) {
    HrSettings.AttendanceCapture c = settings.getAttendanceCapture();
    c.setLocationCheck(true);
    c.setShopLat(12.9716);
    c.setShopLng(77.5946);
    c.setRadiusMetres(100);
    c.setOutsideAction(action);
  }

  private void advance(Duration by) {
    clock = Clock.fixed(clock.instant().plus(by), ZoneOffset.UTC);
    service.setClock(clock);
  }

  private ErrorCode refused(AppPunchRequest request) {
    int before = stored.size();
    BaseException e = assertThrows(BaseException.class, () -> service.punch(pairing, request));
    assertEquals(before, stored.size(), "a refusal stores nothing");
    verify(punchDays, never()).writeDerived(any(), any(), any(), any(), any());
    verify(pairingService, never()).recordLastPunch(any(), any());
    return e.getErrorCode();
  }

  private long resultIncrements() {
    return mockingDetails(metrics).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("increment"))
        .filter(i -> HrMetricsConstants.APP_PUNCHES_TOTAL.equals(i.getArgument(0)))
        .count();
  }

  /** Exactly one result counter for this shop, with that label (R10.1, R10.2). */
  private void assertOneResult(String result) {
    verify(metrics).increment(HrMetricsConstants.APP_PUNCHES_TOTAL, 1, "module", "hr", "shop",
        SHOP_ID, "result", result);
    assertEquals(1, resultIncrements(), "exactly one result label per attempt");
  }

  // ---- accepted punches ------------------------------------------------------------------------

  @Test
  void onlinePunchIsStoredWithServerTimeWhateverThePhoneSaid() throws Exception {
    String punchId = newPunchId();
    Instant phoneTime = NOW.minus(Duration.ofMinutes(7));

    AppPunchResponse r = service.punch(pairing, signed(online(punchId, phoneTime)));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertFalse(r.isDuplicate());
    assertEquals(PunchDirection.IN, r.getDirection());
    assertEquals(NOW, r.getAt());
    assertEquals("09:12", r.getAtLocal());
    assertEquals(List.of(), r.getFlags());
    assertEquals("09:12", r.getTodayIn());
    assertNull(r.getTodayOut());
    assertEquals(PunchDirection.OUT, r.getNextDirection());

    Punch p = stored.get(0);
    assertEquals(NOW, p.getAt());
    assertEquals(TODAY, p.getLocalDate());
    assertEquals(phoneTime, p.getPhoneTime());
    assertEquals(PunchSource.BIOMETRIC_APP, p.getSource());
    assertEquals(PAIRING_ID, p.getPairingId());
    assertEquals(PHONE, p.getDeviceModel());
    assertEquals("B:" + EMP_ID + ":" + punchId, p.getDedupeKey());
    assertEquals(AppSignatures.sha256Hex(AppSignatures.punchPayload(online(punchId, phoneTime))),
        p.getPayloadSha256());
    assertEquals(List.of(), p.getFlags());
    verify(punchDays).writeDerived(eq(SHOP_ID), eq(employee), eq(TODAY), eq(settings), any());
    verify(pairingService).recordLastPunch(PAIRING_ID, NOW);
    assertOneResult(AppPunchResult.ACCEPTED);
    verify(metrics, never()).increment(eq(HrMetricsConstants.APP_OFFLINE_BACKLOG_TOTAL),
        eq(1.0), any(), any(), any(), any());
  }

  @Test
  void offlinePunchIsStoredAtThePhonesTimeWithTheOfflineFlag() throws Exception {
    Instant phoneTime = NOW.minus(Duration.ofHours(2));

    AppPunchResponse r = service.punch(pairing, signed(offline(newPunchId(), phoneTime)));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals(phoneTime, r.getAt());
    assertEquals("07:12", r.getAtLocal());
    assertEquals(List.of(PunchFlag.RECORDED_OFFLINE), r.getFlags());
    Punch p = stored.get(0);
    assertEquals(phoneTime, p.getAt());
    assertEquals(phoneTime, p.getPhoneTime());
    assertEquals(TODAY, p.getLocalDate());
    verify(pairingService).recordLastPunch(PAIRING_ID, phoneTime);
    assertOneResult(AppPunchResult.OFFLINE_ACCEPTED);
    // more than an hour late: counted for the backlog panel (R10.7b)
    verify(metrics).increment(HrMetricsConstants.APP_OFFLINE_BACKLOG_TOTAL, 1, "module", "hr",
        "shop", SHOP_ID);
  }

  @Test
  void anOfflinePunchWithinAnHourIsNotBacklog() throws Exception {
    service.punch(pairing, signed(offline(newPunchId(), NOW.minus(Duration.ofMinutes(59)))));

    assertOneResult(AppPunchResult.OFFLINE_ACCEPTED);
    verify(metrics, never()).increment(eq(HrMetricsConstants.APP_OFFLINE_BACKLOG_TOTAL),
        eq(1.0), any(), any(), any(), any());
  }

  @Test
  void anOfflinePunchIsJudgedForThePhonesDay() throws Exception {
    // left yesterday: refused today, but an offline punch made yesterday still counts (R6.10)
    employee.setExitDate("2025-04-07");
    assertEquals(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED, refused(signed(online(newPunchId(), NOW))));

    // 10:00 IST yesterday, 23 h 12 min ago: inside the 24-hour window
    Instant yesterday = Instant.parse("2025-04-07T04:30:00Z");
    AppPunchResponse r = service.punch(pairing, signed(offline(newPunchId(), yesterday)));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals("2025-04-07", stored.get(0).getLocalDate());
    verify(punchDays).writeDerived(eq(SHOP_ID), eq(employee), eq("2025-04-07"), eq(settings),
        any());
  }

  // ---- offline windows (R6.11, R6.12) ----------------------------------------------------------

  @Test
  void aPhoneClockMoreThanFiveMinutesAheadIsRefused() throws Exception {
    Instant ahead = NOW.plus(Duration.ofMinutes(5)).plusSeconds(1);

    assertEquals(ErrorCode.HR_APP_CLOCK_WRONG, refused(signed(offline(newPunchId(), ahead))));
    assertOneResult(AppPunchResult.OFFLINE_CLOCK_WRONG);
  }

  @Test
  void exactlyFiveMinutesAheadIsAccepted() throws Exception {
    AppPunchResponse r = service.punch(pairing,
        signed(offline(newPunchId(), NOW.plus(Duration.ofMinutes(5)))));

    assertEquals(Outcome.STORED, r.getOutcome());
  }

  @Test
  void anOfflinePunchOlderThanADayIsRefused() throws Exception {
    Instant old = NOW.minus(Duration.ofHours(24)).minusSeconds(1);

    assertEquals(ErrorCode.HR_APP_TOO_LATE, refused(signed(offline(newPunchId(), old))));
    assertOneResult(AppPunchResult.OFFLINE_TOO_LATE);
  }

  @Test
  void exactlyTwentyFourHoursOldIsAccepted() throws Exception {
    AppPunchResponse r = service.punch(pairing,
        signed(offline(newPunchId(), NOW.minus(Duration.ofHours(24)))));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals("2025-04-07", stored.get(0).getLocalDate());
  }

  @Test
  void theWindowsDoNotApplyToAnOnlinePunch() throws Exception {
    AppPunchResponse r = service.punch(pairing,
        signed(online(newPunchId(), NOW.minus(Duration.ofDays(3)))));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals(NOW, r.getAt());
  }

  // ---- signature and replay (R5.7, R5.8, R5.10, R5.11) -----------------------------------------

  @Test
  void aSignatureFromAnotherKeyIsRefusedAndNothingIsStored() throws Exception {
    AppPunchRequest r = signed(online(newPunchId(), NOW), keyPair());

    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED, refused(r));
    assertOneResult(AppPunchResult.SIGNATURE_FAILED);
  }

  @Test
  void aFieldChangedAfterSigningIsRefused() throws Exception {
    AppPunchRequest r = signed(located(newPunchId(), 500, 10, false));
    locationCheck(OutsideAction.BLOCK);
    r.setDistanceMetres(20);

    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED, refused(r));
    assertOneResult(AppPunchResult.SIGNATURE_FAILED);
  }

  @Test
  void aRequestAboutAnotherEmployeeIsRefusedEvenWhenWellSigned() throws Exception {
    String other = "9c9c9c9c9c9c9c9c9c9c9c9c";
    AppPunchRequest r = signed(new PunchFields(PAIRING_ID, SHOP_ID, other, newPunchId(), NOW,
        false, false, null, null, false));

    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED, refused(r));
  }

  @Test
  void aResendOfTheSamePunchAnswersTheStoredResultAndStoresNothingTwice() throws Exception {
    String punchId = newPunchId();
    AppPunchRequest r = signed(online(punchId, NOW));
    AppPunchResponse first = service.punch(pairing, r);
    // the switch may even have been turned off meanwhile: the stored answer still stands
    settings.getAttendanceCapture().setBiometricApp(false);
    advance(Duration.ofMinutes(20));

    AppPunchResponse again = service.punch(pairing, r);

    assertEquals(1, stored.size());
    assertEquals(Outcome.REPLAYED, again.getOutcome());
    assertEquals(first.getAt(), again.getAt());
    assertEquals(first.getDirection(), again.getDirection());
    assertEquals(first.getFlags(), again.getFlags());
    assertFalse(again.isDuplicate());
    assertEquals(2, resultIncrements());
    verify(metrics).increment(HrMetricsConstants.APP_PUNCHES_TOTAL, 1, "module", "hr", "shop",
        SHOP_ID, "result", AppPunchResult.REPLAYED);
  }

  @Test
  void aResendWithDifferentContentIsRefusedAsTampering() throws Exception {
    String punchId = newPunchId();
    service.punch(pairing, signed(online(punchId, NOW)));

    AppPunchRequest changed = signed(online(punchId, NOW.minusSeconds(60)));

    BaseException e = assertThrows(BaseException.class, () -> service.punch(pairing, changed));
    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED, e.getErrorCode());
    assertEquals(1, stored.size());
    verify(metrics).increment(HrMetricsConstants.APP_PUNCHES_TOTAL, 1, "module", "hr", "shop",
        SHOP_ID, "result", AppPunchResult.SIGNATURE_FAILED);
    // the health window saw one good attempt and one signature failure (R10.4)
    verify(health).record(SHOP_ID, false);
    verify(health).record(SHOP_ID, true);
  }

  @Test
  void aHealthFailureNeverChangesThePunchAnswer() throws Exception {
    doThrow(new RuntimeException("health down")).when(health).record(any(), anyBoolean());

    AppPunchResponse r = service.punch(pairing, signed(online(newPunchId(), NOW)));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals(1, stored.size());
    assertOneResult(AppPunchResult.ACCEPTED);
  }

  // ---- duplicates (R4.8) -----------------------------------------------------------------------

  @Test
  void aSecondTapWithinFiveMinutesIsStoredAsADuplicate() throws Exception {
    service.punch(pairing, signed(online(newPunchId(), NOW)));
    advance(Duration.ofMinutes(2));

    AppPunchResponse r = service.punch(pairing,
        signed(online(newPunchId(), clock.instant())));

    assertEquals(2, stored.size());
    assertEquals(Outcome.DUPLICATE, r.getOutcome());
    assertTrue(r.isDuplicate());
    assertEquals("09:12", r.getTodayIn());
    assertNull(r.getTodayOut());
    assertEquals(PunchDirection.OUT, r.getNextDirection());
    verify(metrics).increment(HrMetricsConstants.APP_PUNCHES_TOTAL, 1, "module", "hr", "shop",
        SHOP_ID, "result", AppPunchResult.DUPLICATE);
  }

  @Test
  void aSecondTapAfterFiveMinutesIsTheCheckOut() throws Exception {
    service.punch(pairing, signed(online(newPunchId(), NOW)));
    advance(Duration.ofHours(9));

    AppPunchResponse r = service.punch(pairing,
        signed(online(newPunchId(), clock.instant())));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals(PunchDirection.OUT, r.getDirection());
    assertEquals("09:12", r.getTodayIn());
    assertEquals("18:12", r.getTodayOut());
  }

  // ---- refusals, in the My HR order ------------------------------------------------------------

  @Test
  void theSwitchOffRefusesEveryPunch() throws Exception {
    settings.getAttendanceCapture().setBiometricApp(false);

    assertEquals(ErrorCode.HR_APP_OFF, refused(signed(online(newPunchId(), NOW))));
    assertOneResult(AppPunchResult.OFF);
  }

  @Test
  void anUnpairedPhoneIsRefused() throws Exception {
    pairing.setStatus(PairingStatus.UNPAIRED);

    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, refused(signed(online(newPunchId(), NOW))));
    assertOneResult(AppPunchResult.NOT_PAIRED);
  }

  @Test
  void theSwitchIsCheckedBeforeThePairing() throws Exception {
    settings.getAttendanceCapture().setBiometricApp(false);
    pairing.setStatus(PairingStatus.UNPAIRED);

    assertEquals(ErrorCode.HR_APP_OFF, refused(signed(online(newPunchId(), NOW))));
  }

  // ---- the switch, per punch (R1.3) and off → on (R1.5) ---------------------------------------

  /** A saved switch value, as {@code effectiveSettings} would serve it on the next read. */
  private HrSettings switched(boolean biometricApp) {
    HrSettings s = HrSettingsService.withDefaults(null, SHOP_ID);
    s.getAttendanceCapture().setBiometricApp(biometricApp);
    return s;
  }

  @Test
  void theSwitchIsReadOnEveryPunchSoASavedChangeAppliesToTheVeryNextOne() throws Exception {
    // three saves between three punches: on, off, on; nothing is cached across punches
    when(settingsService.effectiveSettings(SHOP_ID))
        .thenReturn(switched(true), switched(false), switched(true));

    assertEquals(Outcome.STORED,
        service.punch(pairing, signed(online(newPunchId(), NOW))).getOutcome());
    advance(Duration.ofHours(1));
    AppPunchRequest whileOff = signed(online(newPunchId(), clock.instant()));
    BaseException e = assertThrows(BaseException.class, () -> service.punch(pairing, whileOff));
    assertEquals(ErrorCode.HR_APP_OFF, e.getErrorCode());
    assertEquals(1, stored.size(), "the refused tap stored nothing");
    advance(Duration.ofHours(1));
    AppPunchResponse third = service.punch(pairing,
        signed(online(newPunchId(), clock.instant())));

    assertEquals(Outcome.STORED, third.getOutcome());
    assertEquals(PunchDirection.OUT, third.getDirection(), "the refused tap left no punch");
    assertEquals(2, stored.size());
    verify(settingsService, times(3)).effectiveSettings(SHOP_ID);
  }

  @Test
  void turningTheSwitchOffAndBackOnKeepsThePairingAndNeedsNoRePairing() throws Exception {
    settings.getAttendanceCapture().setBiometricApp(false);
    assertEquals(ErrorCode.HR_APP_OFF, refused(signed(online(newPunchId(), NOW))));

    // off deletes nothing: the pairing is never touched, only the punch is refused
    verify(pairingService, never()).unpair(any(), any(), any());
    verify(pairingService, never()).unpairActive(any(), any(), any(), any());
    assertEquals(PairingStatus.ACTIVE, pairing.getStatus());
    assertEquals(spki(punchKey), pairing.getPunchPublicKey());

    // back on: the very same pairing and key are accepted at once
    settings.getAttendanceCapture().setBiometricApp(true);
    AppPunchResponse r = service.punch(pairing, signed(online(newPunchId(), NOW)));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals(PAIRING_ID, stored.get(0).getPairingId());
    verify(pairingService).recordLastPunch(PAIRING_ID, NOW);
  }

  // ---- R7.10: refusals the worker cannot fix end with the sentence ----------------------------

  private static final String ASK_TO_MARK = "Ask your manager to mark your attendance.";

  private String refusalMessage(AppPunchRequest request) {
    return assertThrows(BaseException.class, () -> service.punch(pairing, request)).getMessage();
  }

  @Test
  void refusalsTheWorkerCannotFixEndWithAskYourManagerToMarkYourAttendance() throws Exception {
    // switch off
    settings.getAttendanceCapture().setBiometricApp(false);
    String off = refusalMessage(signed(online(newPunchId(), NOW)));
    settings.getAttendanceCapture().setBiometricApp(true);
    // not verified: another key signed the punch
    String notVerified = refusalMessage(signed(online(newPunchId(), NOW), keyPair()));
    // too late: an offline punch older than 24 hours
    String tooLate = refusalMessage(
        signed(offline(newPunchId(), NOW.minus(Duration.ofHours(25)))));

    assertEquals(ErrorCode.HR_APP_OFF.getMessage(), off);
    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED.getMessage(), notVerified);
    assertEquals(ErrorCode.HR_APP_TOO_LATE.getMessage(), tooLate);
    for (String message : List.of(off, notVerified, tooLate)) {
      assertTrue(message.endsWith(ASK_TO_MARK), message);
      assertEquals(message.lastIndexOf(ASK_TO_MARK), message.length() - ASK_TO_MARK.length(),
          "the sentence is the last line, once: " + message);
    }
    assertEquals(0, stored.size());
  }

  @Test
  void notPairedKeepsTheR32WordingAndFixableRefusalsDoNotCarryTheSentence() throws Exception {
    // R3.2 fixes the whole text for an unpaired phone; the app appends the R7.10 line itself
    // (design "API", notes under the error table)
    pairing.setStatus(PairingStatus.UNPAIRED);
    assertEquals("This phone is no longer paired. Ask your manager.",
        refusalMessage(signed(online(newPunchId(), NOW))));
    pairing.setStatus(PairingStatus.ACTIVE);

    // the worker can fix these: no "mark your attendance" instruction
    String clockWrong = refusalMessage(
        signed(offline(newPunchId(), NOW.plus(Duration.ofMinutes(6)))));
    locationCheck(OutsideAction.BLOCK);
    String fake = refusalMessage(signed(located(newPunchId(), 10, 5, true)));
    String outside = refusalMessage(signed(located(newPunchId(), 500, 5, false)));

    assertEquals(ErrorCode.HR_APP_CLOCK_WRONG.getMessage(), clockWrong);
    assertEquals(ErrorCode.HR_FAKE_LOCATION.getMessage(), fake);
    for (String message : List.of(fake, outside)) {
      assertFalse(message.endsWith(ASK_TO_MARK), message);
    }
    assertFalse(clockWrong.endsWith(ASK_TO_MARK), "clock wrong tells the worker to fix the time");
    assertEquals(0, stored.size());
  }

  @Test
  void aLockedSalaryMonthIsRefused() throws Exception {
    when(periodLockService.lockedPeriods(anyString(), anyCollection()))
        .thenReturn(Set.of("2025-04"));

    assertEquals(ErrorCode.HR_PERIOD_LOCKED, refused(signed(online(newPunchId(), NOW))));
    assertOneResult(AppPunchResult.LOCKED);
  }

  @Test
  void theTwentyFirstPunchInAMinuteIsRefusedBeforeAnyWork() throws Exception {
    when(rateLimiter.tryAcquirePunch(PAIRING_ID)).thenReturn(false);

    assertEquals(ErrorCode.HR_TOO_MANY_ATTEMPTS, refused(signed(online(newPunchId(), NOW))));
    verify(validator, never()).validate(any());
    verify(punchRepository, never()).findByShopIdAndDedupeKey(any(), any());
    assertOneResult(AppPunchResult.RATE_LIMITED);
  }

  @Test
  void aMalformedRequestIs400AndCountedInvalid() throws Exception {
    AppPunchRequest r = signed(online(newPunchId(), NOW));
    r.setPunchId(r.getPunchId().toUpperCase());

    assertThrows(ValidationException.class, () -> service.punch(pairing, r));
    assertEquals(0, stored.size());
    assertOneResult(AppPunchResult.INVALID);
  }

  // ---- location (R4.5, R4.6) -------------------------------------------------------------------

  @Test
  void aMockLocationUnderBlockIsRefused() throws Exception {
    locationCheck(OutsideAction.BLOCK);

    assertEquals(ErrorCode.HR_FAKE_LOCATION,
        refused(signed(located(newPunchId(), 10, 5, true))));
    assertOneResult(AppPunchResult.FAKE_LOCATION);
  }

  @Test
  void aMockLocationUnderFlagIsStoredWithOnlyTheFakeFlagAndNoDistance() throws Exception {
    locationCheck(OutsideAction.FLAG);

    AppPunchResponse r = service.punch(pairing, signed(located(newPunchId(), 10, 5, true)));

    assertEquals(Outcome.STORED, r.getOutcome());
    assertEquals(List.of(PunchFlag.FAKE_LOCATION), r.getFlags());
    assertNull(stored.get(0).getDistanceMetres());
    assertNull(stored.get(0).getAccuracyMetres());
    assertOneResult(AppPunchResult.FLAGGED);
  }

  @Test
  void theExistingRadiusRuleAppliesToThePhonesDistance() throws Exception {
    locationCheck(OutsideAction.BLOCK);
    assertEquals(ErrorCode.HR_OUTSIDE_SHOP,
        refused(signed(located(newPunchId(), 150, 10, false))));

    locationCheck(OutsideAction.FLAG);
    AppPunchResponse r = service.punch(pairing, signed(located(newPunchId(), 150, 10, false)));
    assertEquals(List.of(PunchFlag.OUTSIDE_SHOP), r.getFlags());
    assertEquals(150, stored.get(0).getDistanceMetres());
  }

  @Test
  void anUnexpectedFailureIsCountedAsErrorOnce() throws Exception {
    when(punchRepository.insert(any(Punch.class))).thenThrow(new IllegalStateException("x"));

    assertThrows(IllegalStateException.class,
        () -> service.punch(pairing, signed(online(newPunchId(), NOW))));
    assertOneResult(AppPunchResult.ERROR);
  }

  // ---- status (R4.1) ---------------------------------------------------------------------------

  @Test
  void statusShowsTheButtonAndTodaysTimes() throws Exception {
    service.punch(pairing, signed(online(newPunchId(), NOW)));

    AppStatusResponse s = service.status(pairing);

    assertEquals("Ravi Kumar", s.getEmployeeName());
    assertEquals("Kumar Stores", s.getShopName());
    assertEquals(TODAY, s.getToday());
    assertTrue(s.isBiometricApp());
    assertTrue(s.isCanPunch());
    assertNull(s.getReason());
    assertEquals("09:12", s.getTodayIn());
    assertEquals(PunchDirection.OUT, s.getNextDirection());
    assertFalse(s.getLocation().isLocationCheck());
    assertEquals(1, s.getConsent().getLatestVersion());
    assertEquals(1, s.getConsent().getAgreedVersion());
    assertFalse(s.getConsent().isReconsentRequired());
    // status is not an attempt
    assertEquals(1, resultIncrements());
  }

  @Test
  void statusReportsTheRefusalWithoutThrowing() {
    settings.getAttendanceCapture().setBiometricApp(false);

    AppStatusResponse s = service.status(pairing);

    assertFalse(s.isBiometricApp());
    assertFalse(s.isCanPunch());
    assertEquals(ErrorCode.HR_APP_OFF.getMessage(), s.getReason());
    assertEquals(PunchDirection.IN, s.getNextDirection());
  }

  @Test
  void statusReturnsTheShopLocationToThePairedPhone() {
    locationCheck(OutsideAction.FLAG);

    AppStatusResponse s = service.status(pairing);

    assertTrue(s.getLocation().isLocationCheck());
    assertEquals(12.9716, s.getLocation().getShopLat());
    assertEquals(100, s.getLocation().getRadiusMetres());
    assertEquals(OutsideAction.FLAG, s.getLocation().getOutsideAction());
  }

  // ---- history (R4.10, R8.9) -------------------------------------------------------------------

  @Test
  void historyDefaultsToTheLastThirtyOneDaysIncludingToday() {
    when(punchRepository.findRangeNewestFirst(any(), any(), any(), any())).thenReturn(List.of());

    service.myPunches(pairing, null, null);

    verify(punchRepository).findRangeNewestFirst(SHOP_ID, EMP_ID, "2025-03-09", TODAY);
  }

  @Test
  void historyRefusesMoreThanThirtyOneDaysOrAReversedRange() {
    assertThrows(ValidationException.class,
        () -> service.myPunches(pairing, "2025-03-08", TODAY));
    assertThrows(ValidationException.class,
        () -> service.myPunches(pairing, TODAY, "2025-04-01"));
    assertThrows(ValidationException.class,
        () -> service.myPunches(pairing, "2025-02-30", TODAY));
    verify(punchRepository, never()).findRangeNewestFirst(any(), any(), any(), any());
  }

  @Test
  void historyMarksCountedPunchesPerDayAndNeverShowsAnotherEmployee() throws Exception {
    Punch a = punchAt(EMP_ID, "p1", Instant.parse("2025-04-07T03:30:00Z"));
    Punch dup = punchAt(EMP_ID, "p2", Instant.parse("2025-04-07T03:32:00Z"));
    Punch b = punchAt(EMP_ID, "p3", Instant.parse("2025-04-08T03:42:00Z"));
    Punch other = punchAt("9c9c9c9c9c9c9c9c9c9c9c9c", "p4", Instant.parse("2025-04-08T03:00:00Z"));
    when(punchRepository.findRangeNewestFirst(SHOP_ID, EMP_ID, "2025-04-07", TODAY))
        .thenReturn(List.of(b, other, dup, a));

    List<PunchResponse> rows = service.myPunches(pairing, "2025-04-07", TODAY);

    assertEquals(List.of("p3", "p2", "p1"), rows.stream().map(PunchResponse::getId).toList());
    assertTrue(rows.get(0).isCounted());
    assertFalse(rows.get(1).isCounted(), "2 minutes after a counted punch: a duplicate");
    assertTrue(rows.get(2).isCounted());
    assertEquals("09:12", rows.get(0).getTime());
    assertEquals(List.of(PunchFlag.RECORDED_OFFLINE), rows.get(2).getFlags());
  }

  private static Punch punchAt(String employeeId, String id, Instant at) {
    Punch p = new Punch();
    p.setId(id);
    p.setShopId(SHOP_ID);
    p.setEmployeeId(employeeId);
    p.setAt(at);
    p.setLocalDate(HrDateUtils.format(at.atZone(HrDateUtils.ZONE).toLocalDate()));
    p.setSource(PunchSource.BIOMETRIC_APP);
    p.setDirection(PunchDirection.IN);
    p.setFlags(new ArrayList<>(id.equals("p1") ? List.of(PunchFlag.RECORDED_OFFLINE) : List.of()));
    return p;
  }
}
