package com.inventory.hr.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.AppHealthAlertRepository;
import com.inventory.hr.domain.repository.AppNonceRepository;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.service.AppPunchHealthService;
import com.inventory.hr.service.AppPunchService;
import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.AttendanceAppAuthInterceptor;
import com.inventory.hr.service.AttendanceAppBodyCachingFilter;
import com.inventory.hr.service.WorkerJoinService;
import com.inventory.hr.service.WorkerPairingService;
import com.inventory.hr.service.WorkerSelfService;
import com.inventory.hr.service.WorkerStateService;
import com.inventory.hr.service.ConsentVersions;
import com.inventory.hr.service.DevicePairingService;
import com.inventory.hr.service.EmployeePairingService;
import com.inventory.hr.service.HrAccessService;
import com.inventory.hr.service.HrMongoItSupport;
import com.inventory.hr.service.HrPermissionGuard;
import com.inventory.hr.service.PairingExitJob;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PairingCodes;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.AppPunchValidator;
import com.inventory.hr.validation.JoinRequestInboxValidator;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Attendance app's HTTP surface end to end against a real MongoDB (R2–R6, R8.9; tasks 3.8
 * and 5.10, IT parts): the manager's pairing and health endpoints on the Bearer chain (request
 * attributes {@code userId}/{@code shopId} as {@code AuthenticationInterceptor} leaves them, a
 * real {@link HrPermissionGuard}), the signed-in worker's handshake on {@code
 * /api/v1/hr/worker/pairings/*} through the real {@link WorkerPairingService} and {@link
 * AppRateLimiter} (the only handshake since worker-app D11 / DD4: every employee here is linked to
 * a login and the request carries that login as the Bearer chain would), and every signed call ({@code /me}, {@code /punches}, {@code /consent}, {@code
 * /pairings/me/unpair}) through the real {@link AttendanceAppBodyCachingFilter}, {@link
 * AttendanceAppAuthInterceptor} and {@link AppPunchService} with real P-256 keys: the request
 * envelope signed by the device key, the punch payload by the punch key, exactly as the app does.
 * Runs only with {@code -Dstockkart.test.mongo.uri=…}; every test uses its own shop and
 * installation ids, and one shared clock drives the pairing service, the rate limiter, the
 * interceptor and the punch service.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class AttendanceAppControllerIT {

  private static final String ROOT = "/api/v1/hr/attendance-app";
  /** The Bearer handshake lives here (worker-app DD4). */
  private static final String WORKER_ROOT = "/api/v1/hr/worker";
  private static final String MANAGER = "66aa00000000000000000001";
  /** Holds VIEW_EMPLOYEES only: may look, may not pair. */
  private static final String VIEWER = "66aa00000000000000000002";

  private static HrMongoItSupport it;
  private static MutableClock clock;
  private static MockMvc mvc;
  private static PairingExitJob exitJob;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("appcontroller");
    clock = new MutableClock();
    ReflectionTestUtils.setField(it.pairings, "clock", clock);
    MongoRepositoryFactory repos = new MongoRepositoryFactory(it.template);

    // manager routes behind the real guard: MANAGER may pair and view attendance, VIEWER may only
    // view employees
    HrAccessService access = mock(HrAccessService.class);
    when(access.permissions(eq(MANAGER), anyString()))
        .thenReturn(EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES, HrPermission.VIEW_EMPLOYEES,
            HrPermission.VIEW_ATTENDANCE));
    when(access.permissions(eq(VIEWER), anyString()))
        .thenReturn(EnumSet.of(HrPermission.VIEW_EMPLOYEES));
    HrPermissionGuard guard = new HrPermissionGuard();
    ReflectionTestUtils.setField(guard, "hrAccessService", access);
    EmployeePairingService employeePairing = new EmployeePairingService();
    ReflectionTestUtils.setField(employeePairing, "guard", guard);
    ReflectionTestUtils.setField(employeePairing, "selfServiceResolver", it.selfService);
    ReflectionTestUtils.setField(employeePairing, "pairingService", it.pairings);
    EmployeeController employeeController = new EmployeeController();
    ReflectionTestUtils.setField(employeeController, "employeeService", it.employeesAs(
        EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES, HrPermission.VIEW_EMPLOYEES), false,
        List.of()));
    ReflectionTestUtils.setField(employeeController, "pairingService", employeePairing);

    // the manager's health read, on the Bearer chain, fed by the punch path below
    AppPunchHealthService health = new AppPunchHealthService(
        repos.getRepository(AppHealthAlertRepository.class), guard, clock);
    AttendanceAppHealthController healthController = new AttendanceAppHealthController();
    ReflectionTestUtils.setField(healthController, "healthService", health);

    // the phone's endpoints: the real rate limiter and the real punch service on the shared clock
    AppRateLimiter rateLimiter = new AppRateLimiter(it.metrics, clock);
    ConsentVersions consents = new ConsentVersions();
    AppPunchService punches = new AppPunchService();
    ReflectionTestUtils.setField(punches, "settingsService",
        ReflectionTestUtils.getField(it.pairings, "settingsService"));
    ReflectionTestUtils.setField(punches, "employeeRepository",
        repos.getRepository(EmployeeRepository.class));
    ReflectionTestUtils.setField(punches, "selfCheckIn", it.selfCheckIn);
    ReflectionTestUtils.setField(punches, "punchDays", it.punchDays);
    ReflectionTestUtils.setField(punches, "punchRepository",
        repos.getRepository(PunchRepository.class));
    ReflectionTestUtils.setField(punches, "pairingService", it.pairings);
    ReflectionTestUtils.setField(punches, "rateLimiter", rateLimiter);
    ReflectionTestUtils.setField(punches, "validator", new AppPunchValidator());
    ReflectionTestUtils.setField(punches, "consentVersions", consents);
    ReflectionTestUtils.setField(punches, "metrics", it.metrics);
    ReflectionTestUtils.setField(punches, "health", health);
    ReflectionTestUtils.setField(punches, "clock", clock);
    AttendanceAppController appController = new AttendanceAppController();
    ReflectionTestUtils.setField(appController, "pairingService", it.pairings);
    ReflectionTestUtils.setField(appController, "punchService", punches);
    ReflectionTestUtils.setField(appController, "consentVersions", consents);

    // the handshake, on the Bearer chain: the real WorkerPairingService over the real pairing
    // service and employee repository; the worker self-service (R5.6 rule) is a stub that lets
    // every login through, since who may pair which code is DevicePairingService's rule
    WorkerPairingService workerPairing = new WorkerPairingService();
    ReflectionTestUtils.setField(workerPairing, "pairingService", it.pairings);
    ReflectionTestUtils.setField(workerPairing, "employeeRepository",
        repos.getRepository(EmployeeRepository.class));
    ReflectionTestUtils.setField(workerPairing, "workerAccounts", mock(WorkerAccounts.class));
    WorkerSelfService selfService = mock(WorkerSelfService.class);
    when(selfService.requireWorkerAccess(anyString())).thenAnswer(i -> new AccountView(
        i.getArgument(0), "Worker", i.getArgument(0) + "@example.com", "9876543210", "WORKER",
        null, true));
    WorkerController workerController = new WorkerController();
    ReflectionTestUtils.setField(workerController, "pairingService", workerPairing);
    ReflectionTestUtils.setField(workerController, "selfService", selfService);
    ReflectionTestUtils.setField(workerController, "rateLimiter", rateLimiter);
    ReflectionTestUtils.setField(workerController, "stateService", mock(WorkerStateService.class));
    ReflectionTestUtils.setField(workerController, "joinService", mock(WorkerJoinService.class));
    ReflectionTestUtils.setField(workerController, "validator", new JoinRequestInboxValidator());

    // the envelope check, as CorsConfig registers it: on the device paths only
    AttendanceAppAuthInterceptor interceptor = new AttendanceAppAuthInterceptor();
    ReflectionTestUtils.setField(interceptor, "pairingRepository",
        repos.getRepository(DevicePairingRepository.class));
    ReflectionTestUtils.setField(interceptor, "nonceRepository",
        repos.getRepository(AppNonceRepository.class));
    ReflectionTestUtils.setField(interceptor, "metrics", it.metrics);
    ReflectionTestUtils.setField(interceptor, "health", health);
    ReflectionTestUtils.setField(interceptor, "clock", clock);

    exitJob = new PairingExitJob();
    ReflectionTestUtils.setField(exitJob, "pairingRepository",
        repos.getRepository(DevicePairingRepository.class));
    ReflectionTestUtils.setField(exitJob, "pairingService", it.pairings);

    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    mvc = MockMvcBuilders.standaloneSetup(appController, employeeController, healthController,
            workerController)
        .setControllerAdvice(handler)
        .addFilters(new AttendanceAppBodyCachingFilter())
        .addMappedInterceptors(
            AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS.toArray(String[]::new), interceptor)
        .build();
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  // ---- fixtures --------------------------------------------------------------------------------

  /** A shop with the Biometric app switch as given, Location check off, no weekly off today. */
  private static String shop(String hex2, boolean biometricApp) {
    return shop(hex2, new HrSettings.AttendanceCapture(false, biometricApp, false, null, null,
        100, null));
  }

  /** A shop with the given capture settings and no weekly off today. */
  private static String shop(String hex2, HrSettings.AttendanceCapture capture) {
    String shop = "66b0000000000000000000" + hex2;
    HrSettings s = new HrSettings();
    s.setShopId(shop);
    s.getWeeklyOffSchedule().add(new HrSettings.WeeklyOffEntry("0000-01",
        List.of(HrDateUtils.today().plusDays(1).getDayOfWeek())));
    s.setAttendanceCapture(capture);
    it.template.insert(s);
    return shop;
  }

  /** Location check on around (12.9718, 77.5947), 100 m radius, with the given outside action. */
  private static HrSettings.AttendanceCapture locationChecked(OutsideAction outsideAction) {
    return new HrSettings.AttendanceCapture(false, true, true, 12.971837, 77.594711, 100,
        outsideAction);
  }

  /** Flips the shop's Biometric app switch, as PUT /settings would. */
  private static void biometricApp(String shop, boolean on) {
    it.template.updateFirst(Query.query(Criteria.where("shopId").is(shop)),
        new Update().set("attendanceCapture.biometricApp", on), HrSettings.class);
  }

  private static String installation() {
    return UUID.randomUUID().toString();
  }

  /** A phone: an installation id and its two P-256 key pairs. */
  private record Phone(String installationId, KeyPair punchKey, KeyPair deviceKey) {
    static Phone create() throws Exception {
      return new Phone(installation(), p256(), p256());
    }
  }

  private static KeyPair p256() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String spki(KeyPair key) {
    return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
  }

  /** Base64 DER ECDSA over {@code payload} with the private half of {@code key}. */
  private static String sign(KeyPair key, byte[] payload) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key.getPrivate());
    signer.update(payload);
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  /**
   * The ten signed fields of a punch as the app fills them (design "Canonical payloads"). {@link
   * #body} is the JSON the app posts, with the signature the punch key made over the canonical
   * payload; the server rebuilds the same bytes from the fields.
   */
  private record Draft(String pairingId, String shopId, String employeeId, String punchId,
      Instant phoneTime, boolean offline, boolean locationChecked, Integer distanceMetres,
      Integer accuracyMetres, boolean mock) {

    /** An online tap now, Location check off on the phone's side. */
    static Draft online(String pairingId, String shop, Employee e) {
      return new Draft(pairingId, shop, e.getId(), UUID.randomUUID().toString(),
          clock.instant(), false, false, null, null, false);
    }

    /** The same punch saved on the phone at {@code phoneTime} and sent now. */
    Draft offlineAt(Instant phoneTime) {
      return new Draft(pairingId, shopId, employeeId, punchId, phoneTime, true, locationChecked,
          distanceMetres, accuracyMetres, mock);
    }

    /** With the phone-computed distance and accuracy, and the mock flag. */
    Draft located(Integer distanceMetres, Integer accuracyMetres, boolean mock) {
      return new Draft(pairingId, shopId, employeeId, punchId, phoneTime, offline, true,
          distanceMetres, accuracyMetres, mock);
    }

    PunchFields fields() {
      return new PunchFields(pairingId, shopId, employeeId, punchId, phoneTime, offline,
          locationChecked, distanceMetres, accuracyMetres, mock);
    }

    String payloadSha256() {
      return AppSignatures.sha256Hex(AppSignatures.punchPayload(fields()));
    }

    String dedupeKey() {
      return "B:" + employeeId + ":" + punchId;
    }

    /** The request body, signed with {@code punchKey} (normally the phone's punch key). */
    String body(KeyPair punchKey) throws Exception {
      return """
          {"pairingId":"%s","shopId":"%s","employeeId":"%s","punchId":"%s","phoneTime":"%s",
           "offline":%s,"locationChecked":%s,"distanceMetres":%s,"accuracyMetres":%s,
           "mock":%s,"signature":"%s"}
          """.formatted(pairingId, shopId, employeeId, punchId,
          DateTimeFormatter.ISO_INSTANT.format(phoneTime), offline, locationChecked,
          distanceMetres, accuracyMetres, mock,
          sign(punchKey, AppSignatures.punchPayload(fields())));
    }
  }

  // ---- HTTP helpers ----------------------------------------------------------------------------

  /** What {@code AuthenticationInterceptor} leaves on a Bearer request of {@code userId}. */
  private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder r,
      String userId, String shop) {
    return r.requestAttr("userId", userId).requestAttr("shopId", shop);
  }

  private static String json(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  /** {@code POST /employees/{id}/pairing-codes} as the manager; returns the clear-text code. */
  private static String createCode(String shop, Employee e) throws Exception {
    MvcResult r = mvc.perform(asUser(post("/api/v1/hr/employees/" + e.getId() + "/pairing-codes"),
            MANAGER, shop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.code").isString())
        .andExpect(jsonPath("$.data.expiresAt").exists())
        .andReturn();
    String code = JsonPath.read(json(r), "$.data.code");
    assertEquals(PairingCodes.qrPayload(code), JsonPath.read(json(r), "$.data.qrPayload"));
    return code;
  }

  private static String claimBody(String code, String installationId, String model) {
    return """
        {"code":"%s","installationId":"%s","os":"ANDROID","osVersion":"14",
         "phoneModel":"%s","appVersion":"1.0.0"}
        """.formatted(code, installationId, model);
  }

  /** Step one on the Bearer path, as the employee's own login (worker-app R7.2–R7.3). */
  private static MockHttpServletRequestBuilder claim(Employee e, String code,
      String installationId, String model) {
    return asWorker(post(WORKER_ROOT + "/pairings/claim").contentType(MediaType.APPLICATION_JSON)
        .content(claimBody(code, installationId, model)), e);
  }

  private static MockHttpServletRequestBuilder claim(Employee e, String code,
      String installationId) {
    return claim(e, code, installationId, "Redmi Note 11");
  }

  /** Step one as the signed-in worker; returns the body (claim token and consent version). */
  private static String claimOk(Employee e, String code, Phone phone, String model)
      throws Exception {
    return json(mvc.perform(claim(e, code, phone.installationId(), model))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.claimToken").isString())
        .andExpect(jsonPath("$.data.shopName").value("Test Shop"))
        .andExpect(jsonPath("$.data.consent.version").isNumber())
        .andReturn());
  }

  /** Step two on the Bearer path, as the employee's own login. */
  private static MockHttpServletRequestBuilder complete(Employee e, String token, Phone phone,
      int consent) {
    return asWorker(post(WORKER_ROOT + "/pairings/complete")
        .contentType(MediaType.APPLICATION_JSON).content("""
        {"claimToken":"%s","installationId":"%s","punchPublicKey":"%s",
         "devicePublicKey":"%s","consentVersion":%d}
        """.formatted(token, phone.installationId(), spki(phone.punchKey()),
        spki(phone.deviceKey()), consent)), e);
  }

  /** "No, this isn't me" on the Bearer path, as the employee's own login. */
  private static MockHttpServletRequestBuilder abandon(Employee e, String token) {
    return asWorker(post(WORKER_ROOT + "/pairings/abandon")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"claimToken\":\"" + token + "\"}"), e);
  }

  /** The whole handshake for {@code phone} as {@code e}'s login; returns the new pairing id. */
  private static String pair(String shop, Employee e, Phone phone, String model)
      throws Exception {
    String code = createCode(shop, e);
    String claimed = claimOk(e, code, phone, model);
    String token = JsonPath.read(claimed, "$.data.claimToken");
    int consent = JsonPath.read(claimed, "$.data.consent.version");
    MvcResult r = mvc.perform(complete(e, token, phone, consent))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.pairingId").isString())
        .andExpect(jsonPath("$.data.shopId").value(shop))
        .andExpect(jsonPath("$.data.employeeId").value(e.getId()))
        .andExpect(jsonPath("$.data.employeeName").value(e.getName()))
        .andExpect(jsonPath("$.data.shopName").value("Test Shop"))
        .andExpect(jsonPath("$.data.location.locationCheck").isBoolean())
        .andExpect(jsonPath("$.data.consentVersion").value(consent))
        .andReturn();
    return JsonPath.read(json(r), "$.data.pairingId");
  }

  /** {@code GET /me} signed with the phone's device key, as the app sends it. */
  private static MockHttpServletRequestBuilder signedMe(String pairingId, Phone phone)
      throws Exception {
    return signed(pairingId, phone.deviceKey(), get(ROOT + "/me"), null, null);
  }

  /** A signed {@code POST /punches} carrying {@code body}, as the app sends it. */
  private static MockHttpServletRequestBuilder signedPunch(String pairingId, Phone phone,
      String body) throws Exception {
    return signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"), null, body);
  }

  /** A signed {@code GET /punches}, {@code query} being the raw query string or null. */
  private static MockHttpServletRequestBuilder signedHistory(String pairingId, Phone phone,
      String query) throws Exception {
    String url = ROOT + "/punches" + (query != null ? "?" + query : "");
    return signed(pairingId, phone.deviceKey(), get(url), query, null);
  }

  /** A signed {@code POST} to {@code path} under the app root with {@code body} (may be null). */
  private static MockHttpServletRequestBuilder signedPost(String pairingId, Phone phone,
      String path, String body) throws Exception {
    return signed(pairingId, phone.deviceKey(), post(ROOT + path), null, body);
  }

  /** The envelope of design "Canonical payloads" for {@code request}, a fresh nonce, now. */
  private static MockHttpServletRequestBuilder signed(String pairingId, KeyPair deviceKey,
      MockHttpServletRequestBuilder request, String query, String body) throws Exception {
    return signed(pairingId, deviceKey, request, query, body, clock.instant().getEpochSecond(),
        UUID.randomUUID().toString());
  }

  /**
   * The envelope with every input in the caller's hands: the device key that signs it, the
   * timestamp and nonce it carries. The signed path and method are taken from the builder, so a
   * mismatch can only come from the body or the key.
   */
  private static MockHttpServletRequestBuilder signed(String pairingId, KeyPair deviceKey,
      MockHttpServletRequestBuilder request, String query, String body, long timestamp,
      String nonce) throws Exception {
    MockHttpServletRequest probe = request.buildRequest(new MockServletContext());
    byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
    byte[] payload = AppSignatures.requestEnvelope(new EnvelopeFields(pairingId,
        probe.getMethod(), probe.getRequestURI(), query, timestamp, nonce, bodyBytes));
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(bodyBytes);
    }
    return request
        .header(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, pairingId)
        .header(AttendanceAppAuthInterceptor.HEADER_TIMESTAMP, Long.toString(timestamp))
        .header(AttendanceAppAuthInterceptor.HEADER_NONCE, nonce)
        .header(AttendanceAppAuthInterceptor.HEADER_SIGNATURE, sign(deviceKey, payload));
  }

  private static void expectRefusal(MockHttpServletRequestBuilder request, ErrorCode code)
      throws Exception {
    mvc.perform(request)
        .andExpect(status().is(code.getHttpStatus().value()))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(code.getMessage()))
        .andExpect(jsonPath("$.data.message").value(code.getMessage()))
        .andExpect(jsonPath("$.data.status").value(code.getHttpStatus().value()));
  }

  // ---- Mongo reads -----------------------------------------------------------------------------

  private static DevicePairing pairing(String id) {
    return it.template.findById(id, DevicePairing.class);
  }

  private static List<PairingCode> codes(String shop) {
    return it.template.find(Query.query(Criteria.where("shopId").is(shop)), PairingCode.class);
  }

  private static List<HrAuditEvent> audits(String shop, HrAuditAction action) {
    return it.template.find(Query.query(Criteria.where("shopId").is(shop)
        .and("action").is(action)), HrAuditEvent.class);
  }

  private static String rawJson(String collection, String shop) {
    StringBuilder all = new StringBuilder();
    for (Document d : it.template.getCollection(collection).find(new Document("shopId", shop))) {
      all.append(d.toJson());
    }
    return all.toString();
  }

  private static List<Punch> punches(String shop) {
    return it.template.find(Query.query(Criteria.where("shopId").is(shop))
        .with(Sort.by("at")), Punch.class);
  }

  private static AttendanceDay day(String shop, Employee e, String date) {
    return it.template.findOne(Query.query(Criteria.where("shopId").is(shop)
        .and("employeeId").is(e.getId()).and("date").is(date)), AttendanceDay.class);
  }

  private static String today() {
    return HrDateUtils.format(HrDateUtils.today());
  }

  private static String dayOf(Instant at) {
    return HrDateUtils.format(at.atZone(HrDateUtils.ZONE).toLocalDate());
  }

  /** A stored app punch for history tests, at {@code at}, counted or not as the day decides. */
  private static Punch storedPunch(String shop, Employee e, String pairingId, Instant at) {
    Punch p = new Punch();
    p.setShopId(shop);
    p.setEmployeeId(e.getId());
    p.setAt(at);
    p.setLocalDate(dayOf(at));
    p.setSource(PunchSource.BIOMETRIC_APP);
    p.setDirection(PunchDirection.IN);
    p.setFlags(new ArrayList<>());
    p.setDedupeKey("B:" + e.getId() + ":" + UUID.randomUUID());
    p.setPairingId(pairingId);
    p.setDeviceModel("Redmi Note 11");
    p.setPhoneTime(at);
    p.setCreatedAt(at);
    return it.template.insert(p);
  }

  /**
   * An ACTIVE employee linked to its own login (worker-app D11: every pairing is made by a signed-in
   * worker for their own record). The login is derived from the shop and code, so each test has its
   * own and the handshake helpers below can name the caller from the employee.
   */
  private static Employee worker(String shop, String code, String name) {
    return it.employee(shop, code, name, "login-" + shop.substring(shop.length() - 2) + "-" + code);
  }

  /** What {@code AuthenticationInterceptor} leaves on a request of the employee's own login. */
  private static MockHttpServletRequestBuilder asWorker(MockHttpServletRequestBuilder r,
      Employee e) {
    return r.requestAttr("userId", e.getUserId()).requestAttr("shopId", e.getShopId());
  }

  // ---- happy path ------------------------------------------------------------------------------

  @Test
  void managerCodePhoneClaimAndCompleteMakeAnActivePairing() throws Exception {
    String shop = shop("01", true);
    Employee asha = worker(shop, "EMP-001", "Asha");
    Phone phone = Phone.create();

    // before: Not paired, no pending code
    mvc.perform(asUser(get("/api/v1/hr/employees/" + asha.getId() + "/pairing"), MANAGER, shop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("NOT_PAIRED"))
        .andExpect(jsonPath("$.data.pendingCode").doesNotExist());

    String code = createCode(shop, asha);
    assertTrue(PairingCodes.isValid(code), code);
    // only the hash is stored (R9.11); the manager's page sees a pending code
    List<PairingCode> stored = codes(shop);
    assertEquals(1, stored.size());
    assertEquals(PairingCodes.hash(code), stored.get(0).getCodeHash());
    assertEquals(PairingCodeStatus.UNUSED, stored.get(0).getStatus());
    assertFalse(rawJson("hr_pairing_codes", shop).contains(code));
    assertEquals(1, audits(shop, HrAuditAction.PAIRING_CODE_CREATED).size());
    mvc.perform(asUser(get("/api/v1/hr/employees/" + asha.getId() + "/pairing"), MANAGER, shop))
        .andExpect(jsonPath("$.data.state").value("NOT_PAIRED"))
        .andExpect(jsonPath("$.data.pendingCode.expiresAt").exists());

    // claim: with Asha's login on the worker path (DD4); typing is forgiving (R2.3)
    String typed = code.substring(0, 4).toLowerCase() + " " + code.substring(4);
    String claimed = claimOk(asha, typed, phone, "Redmi Note 11");
    assertEquals("Asha", JsonPath.read(claimed, "$.data.employeeName"));
    String token = JsonPath.read(claimed, "$.data.claimToken");
    int consent = JsonPath.read(claimed, "$.data.consent.version");
    assertEquals(PairingCodeStatus.CLAIMED, codes(shop).get(0).getStatus());
    assertFalse(rawJson("hr_pairing_codes", shop).contains(token), "token stored hashed only");

    // complete with real P-256 keys
    MvcResult r = mvc.perform(complete(asha, token, phone, consent))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.employeeName").value("Asha"))
        .andExpect(jsonPath("$.data.location.locationCheck").value(false))
        .andReturn();
    String pairingId = JsonPath.read(json(r), "$.data.pairingId");

    DevicePairing p = pairing(pairingId);
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(shop, p.getShopId());
    assertEquals(asha.getId(), p.getEmployeeId());
    assertEquals(phone.installationId(), p.getInstallationId());
    assertEquals(spki(phone.punchKey()), p.getPunchPublicKey());
    assertEquals(spki(phone.deviceKey()), p.getDevicePublicKey());
    assertEquals(DeviceOs.ANDROID, p.getOs());
    assertEquals("Redmi Note 11", p.getPhoneModel());
    assertEquals(MANAGER, p.getPairedBy());
    assertEquals(consent, p.getConsentVersion());
    assertEquals(1, p.getConsents().size());
    assertEquals(clock.instant(), p.getPairedAt());
    assertEquals(clock.instant(), p.getConsentAt());
    assertTrue(codes(shop).isEmpty(), "the used code is deleted");

    List<HrAuditEvent> paired = audits(shop, HrAuditAction.DEVICE_PAIRED);
    assertEquals(1, paired.size());
    assertEquals(pairingId, paired.get(0).getEntityId());
    assertEquals(asha.getId(), paired.get(0).getEmployeeId());
    assertEquals(MANAGER, paired.get(0).getActorUserId());
    assertEquals("Redmi Note 11", paired.get(0).getAfter().get("phoneModel"));
    String audit = rawJson("hr_audit_events", shop);
    assertFalse(audit.contains(phone.installationId()), "no installation id in audit");
    assertFalse(audit.contains(spki(phone.punchKey())) || audit.contains(spki(phone.deviceKey())),
        "no key in audit");

    // the manager's page and the phone's home screen
    mvc.perform(asUser(get("/api/v1/hr/employees/" + asha.getId() + "/pairing"), MANAGER, shop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("PAIRED"))
        .andExpect(jsonPath("$.data.phoneModel").value("Redmi Note 11"))
        .andExpect(jsonPath("$.data.os").value("ANDROID"))
        .andExpect(jsonPath("$.data.pairedBy").value(MANAGER))
        .andExpect(jsonPath("$.data.lastPunchAt").doesNotExist())
        .andExpect(jsonPath("$.data.pendingCode").doesNotExist());
    mvc.perform(signedMe(pairingId, phone))
        .andExpect(status().isOk())
        .andExpect(header().exists(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.employeeName").value("Asha"))
        .andExpect(jsonPath("$.data.shopName").value("Test Shop"))
        .andExpect(jsonPath("$.data.biometricApp").value(true))
        .andExpect(jsonPath("$.data.canPunch").value(true))
        .andExpect(jsonPath("$.data.nextDirection").value("IN"))
        .andExpect(jsonPath("$.data.consent.agreedVersion").value(consent))
        .andExpect(jsonPath("$.data.consent.reconsentRequired").value(false));
  }

  @Test
  void aViewerMayNotCreateACodeOrUnpair() throws Exception {
    String shop = shop("02", true);
    Employee e = worker(shop, "EMP-001", "Bala");
    for (MockHttpServletRequestBuilder call : List.of(
        post("/api/v1/hr/employees/" + e.getId() + "/pairing-codes"),
        post("/api/v1/hr/employees/" + e.getId() + "/pairing/unpair"),
        get("/api/v1/hr/employees/" + e.getId() + "/pairing"))) {
      mvc.perform(asUser(call, VIEWER, shop))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.error").value("You do not have permission to do this in HR"));
    }
    assertTrue(codes(shop).isEmpty());
    // without a Bearer context at all: 401
    mvc.perform(post("/api/v1/hr/employees/" + e.getId() + "/pairing-codes"))
        .andExpect(status().isUnauthorized());
  }

  // ---- replace and move ------------------------------------------------------------------------

  @Test
  void aSecondPhoneReplacesTheFirstAndTheFirstIsNoLongerPaired() throws Exception {
    String shop = shop("03", true);
    Employee e = worker(shop, "EMP-001", "Chitra");
    Phone old = Phone.create();
    Phone fresh = Phone.create();
    String first = pair(shop, e, old, "Redmi Note 11");
    clock.advance(Duration.ofMinutes(1));

    // the old phone stays paired until the new pairing completes (R2.10)
    String code = createCode(shop, e);
    assertEquals(PairingStatus.ACTIVE, pairing(first).getStatus());
    mvc.perform(signedMe(first, old)).andExpect(status().isOk());
    String claimed = claimOk(e, code, fresh, "Pixel 7a");
    assertEquals(PairingStatus.ACTIVE, pairing(first).getStatus());
    String freshToken = JsonPath.read(claimed, "$.data.claimToken");
    int consent = JsonPath.read(claimed, "$.data.consent.version");
    String second = JsonPath.read(json(mvc.perform(complete(e, freshToken, fresh, consent))
        .andExpect(status().isOk()).andReturn()), "$.data.pairingId");

    DevicePairing replaced = pairing(first);
    assertEquals(PairingStatus.UNPAIRED, replaced.getStatus());
    assertEquals(UnpairReason.REPLACED, replaced.getUnpairReason());
    assertEquals(MANAGER, replaced.getUnpairedBy());
    assertEquals(clock.instant(), replaced.getUnpairedAt());
    assertEquals(clock.instant().plus(Duration.ofDays(90)), replaced.getPurgeAt());
    assertEquals(PairingStatus.ACTIVE, pairing(second).getStatus());
    assertEquals("Pixel 7a", pairing(second).getPhoneModel());
    assertEquals(1, it.template.count(Query.query(Criteria.where("shopId").is(shop)
        .and("employeeId").is(e.getId()).and("status").is(PairingStatus.ACTIVE)),
        DevicePairing.class), "at most one active pairing per employee");

    List<HrAuditEvent> audit = audits(shop, HrAuditAction.DEVICE_REPLACED);
    assertEquals(1, audit.size());
    assertEquals(second, audit.get(0).getEntityId());
    assertEquals("Redmi Note 11", audit.get(0).getBefore().get("phoneModel"));
    assertEquals("Pixel 7a", audit.get(0).getAfter().get("phoneModel"));
    assertEquals(UnpairReason.REPLACED.name(), audit.get(0).getReason());
    assertEquals(1, audits(shop, HrAuditAction.DEVICE_PAIRED).size(), "first pairing only");

    // the old phone's next signed call: wipe yourself (R3.2)
    expectRefusal(signedMe(first, old), ErrorCode.HR_APP_NOT_PAIRED);
    mvc.perform(signedMe(second, fresh)).andExpect(status().isOk());
    mvc.perform(asUser(get("/api/v1/hr/employees/" + e.getId() + "/pairing"), MANAGER, shop))
        .andExpect(jsonPath("$.data.state").value("PAIRED"))
        .andExpect(jsonPath("$.data.phoneModel").value("Pixel 7a"));
  }

  @Test
  void anInstallationPairedToAnotherEmployeeLeavesTheFirstOne() throws Exception {
    String shop = shop("04", true);
    Employee dev = worker(shop, "EMP-001", "Dev");
    Employee esha = worker(shop, "EMP-002", "Esha");
    Phone shared = Phone.create();
    String devs = pair(shop, dev, shared, "Galaxy A14");
    clock.advance(Duration.ofMinutes(1));

    // the same installation (new keys, as a reinstall would make) pairs with Esha
    Phone again = new Phone(shared.installationId(), p256(), p256());
    String eshas = pair(shop, esha, again, "Galaxy A14");

    DevicePairing moved = pairing(devs);
    assertEquals(PairingStatus.UNPAIRED, moved.getStatus());
    assertEquals(UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE, moved.getUnpairReason());
    assertEquals(MANAGER, moved.getUnpairedBy());
    assertEquals(PairingStatus.ACTIVE, pairing(eshas).getStatus());
    assertEquals(1, it.template.count(Query.query(
        Criteria.where("installationId").is(shared.installationId())
            .and("status").is(PairingStatus.ACTIVE)), DevicePairing.class),
        "at most one active pairing per installation");

    List<HrAuditEvent> unpaired = audits(shop, HrAuditAction.DEVICE_UNPAIRED);
    assertEquals(1, unpaired.size());
    assertEquals(devs, unpaired.get(0).getEntityId());
    assertEquals(dev.getId(), unpaired.get(0).getEmployeeId());
    assertEquals(UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE.name(), unpaired.get(0).getReason());
    assertEquals(2, audits(shop, HrAuditAction.DEVICE_PAIRED).size(), "Esha's is a plain pairing");

    expectRefusal(signedMe(devs, shared), ErrorCode.HR_APP_NOT_PAIRED);
    mvc.perform(asUser(get("/api/v1/hr/employees/" + dev.getId() + "/pairing"), MANAGER, shop))
        .andExpect(jsonPath("$.data.state").value("NOT_PAIRED"));
  }

  // ---- R2.14 refusals, each with its exact message --------------------------------------------

  @Test
  void anExpiredCodeIsRefusedWithItsMessage() throws Exception {
    String shop = shop("05", true);
    Employee e = worker(shop, "EMP-001", "Farah");
    String code = createCode(shop, e);
    clock.advance(Duration.ofMinutes(14).plusSeconds(59));
    String token = JsonPath.read(claimOk(e, code, Phone.create(), "Redmi Note 11"),
        "$.data.claimToken");

    clock.advance(Duration.ofSeconds(1));
    expectRefusal(claim(e, code, installation()), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    // the claim token of an expired code is spent too
    Phone phone = Phone.create();
    expectRefusal(complete(e, token, phone, 1), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    // a token that matches nothing reads as expired as well (used codes are deleted)
    expectRefusal(complete(e, "no-such-token", phone, 1), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    assertEquals(0, it.template.count(Query.query(Criteria.where("shopId").is(shop)),
        DevicePairing.class));
    // the manager's page no longer shows the code as pending
    mvc.perform(asUser(get("/api/v1/hr/employees/" + e.getId() + "/pairing"), MANAGER, shop))
        .andExpect(jsonPath("$.data.pendingCode").doesNotExist());
  }

  @Test
  void aCodeClaimedByAnotherPhoneReadsAsUsed() throws Exception {
    String shop = shop("06", true);
    Employee e = worker(shop, "EMP-001", "Gita");
    String code = createCode(shop, e);
    Phone first = Phone.create();
    String token = JsonPath.read(claimOk(e, code, first, "Redmi Note 11"), "$.data.claimToken");

    expectRefusal(claim(e, code, installation()), ErrorCode.HR_PAIRING_CODE_USED);
    // the first phone may re-send its own code (a lost answer); it gets a fresh token and the
    // earlier one is dead
    String token2 = JsonPath.read(claimOk(e, code, first, "Redmi Note 11"), "$.data.claimToken");
    assertFalse(token.equals(token2));
    expectRefusal(complete(e, token, first, 1), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    // a stranger holding the live token but not the installation: wrong
    expectRefusal(complete(e, token2, Phone.create(), 1), ErrorCode.HR_PAIRING_CODE_WRONG);
  }

  @Test
  void aCancelledCodeIsRefusedWithItsMessage() throws Exception {
    String shop = shop("07", true);
    Employee e = worker(shop, "EMP-001", "Hari");
    String older = createCode(shop, e);
    String newer = createCode(shop, e);
    assertEquals(PairingCodeStatus.CANCELLED, codes(shop).stream()
        .filter(c -> c.getCodeHash().equals(PairingCodes.hash(older))).findFirst().get()
        .getStatus());

    expectRefusal(claim(e, older, installation()), ErrorCode.HR_PAIRING_CODE_CANCELLED);
    claimOk(e, newer, Phone.create(), "Redmi Note 11");
  }

  @Test
  void aWrongCodeIsRefusedWithItsMessage() throws Exception {
    String shop = shop("08", true);
    Employee e = worker(shop, "EMP-001", "Isha");
    expectRefusal(claim(e, "ZZZZ2222", installation()), ErrorCode.HR_PAIRING_CODE_WRONG);
    // a malformed code (wrong length, letters outside the alphabet) is "wrong" to the worker too
    expectRefusal(claim(e, "ABC", installation()), ErrorCode.HR_PAIRING_CODE_WRONG);
    expectRefusal(claim(e, "ABCDEFG0", installation()), ErrorCode.HR_PAIRING_CODE_WRONG);
  }

  @Test
  void aShopWithTheAppOffRefusesTheClaimWithItsMessage() throws Exception {
    String shop = shop("09", false);
    Employee e = worker(shop, "EMP-001", "Jaya");
    String code = createCode(shop, e);
    expectRefusal(claim(e, code, installation()), ErrorCode.HR_APP_OFF);
    assertEquals(PairingCodeStatus.UNUSED, codes(shop).get(0).getStatus());
    assertEquals(1, codes(shop).get(0).getAttempts(), "the attempt is counted on the code");
  }

  @Test
  void anExitedEmployeesCodeIsRefusedWithItsMessage() throws Exception {
    String shop = shop("0a", true);
    Employee e = worker(shop, "EMP-001", "Kiran");
    String code = createCode(shop, e);
    mvc.perform(asUser(post("/api/v1/hr/employees/" + e.getId() + "/exit"), MANAGER, shop)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"exitDate\":\"" + HrDateUtils.format(HrDateUtils.today()) + "\"}"))
        .andExpect(status().isOk());

    // the exited worker's own login has no ACTIVE employee any more: turned away before any code
    // is read (worker-app R7.3; nothing counted on the code)
    expectRefusal(claim(e, code, installation()), ErrorCode.HR_WORKER_NOT_ACTIVE);
    assertEquals(0, codes(shop).get(0).getAttempts() == null ? 0 : codes(shop).get(0).getAttempts());
    // another worker trying the code meets the HR-module refusal first, in its R2.14 order, and
    // no PAIRING_CODE_REFUSED row is written for it
    Employee other = worker(shop, "EMP-002", "Lata");
    expectRefusal(claim(other, code, installation()), ErrorCode.HR_PAIRING_EMPLOYEE_EXITED);
    assertEquals(1, codes(shop).get(0).getAttempts(), "the attempt is counted on the code");
    assertTrue(audits(shop, HrAuditAction.PAIRING_CODE_REFUSED).isEmpty());
    // and no new code for an exited employee
    expectRefusal(asUser(post("/api/v1/hr/employees/" + e.getId() + "/pairing-codes"), MANAGER,
        shop), ErrorCode.HR_PAIRING_EMPLOYEE_EXITED);
  }

  // ---- abandon, attempts, lockout --------------------------------------------------------------

  @Test
  void abandonInvalidatesTheCodeForEveryone() throws Exception {
    String shop = shop("0b", true);
    Employee e = worker(shop, "EMP-001", "Lata");
    String code = createCode(shop, e);
    Phone phone = Phone.create();
    String claimed = claimOk(e, code, phone, "Redmi Note 11");
    String token = JsonPath.read(claimed, "$.data.claimToken");
    int consent = JsonPath.read(claimed, "$.data.consent.version");

    mvc.perform(abandon(e, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.abandoned").value(true));
    assertEquals(PairingCodeStatus.INVALIDATED, codes(shop).get(0).getStatus());

    expectRefusal(complete(e, token, phone, consent), ErrorCode.HR_PAIRING_CODE_CANCELLED);
    expectRefusal(claim(e, code, phone.installationId()), ErrorCode.HR_PAIRING_CODE_CANCELLED);
    expectRefusal(claim(e, code, installation()), ErrorCode.HR_PAIRING_CODE_CANCELLED);
    // idempotent: abandoning again, or an unknown token, is fine
    mvc.perform(abandon(e, token)).andExpect(status().isOk());
    mvc.perform(abandon(e, "nobody")).andExpect(status().isOk());
    assertEquals(0, it.template.count(Query.query(Criteria.where("shopId").is(shop)),
        DevicePairing.class));
    // a fresh code still works for this employee
    pair(shop, e, Phone.create(), "Redmi Note 11");
  }

  @Test
  void aClaimTokenIsSpentByTheCompleteThatUsedIt() throws Exception {
    String shop = shop("1c", true);
    Employee e = worker(shop, "EMP-001", "Yamini");
    Phone phone = Phone.create();
    String code = createCode(shop, e);
    String claimed = claimOk(e, code, phone, "Redmi Note 11");
    String token = JsonPath.read(claimed, "$.data.claimToken");
    int consent = JsonPath.read(claimed, "$.data.consent.version");
    String pairingId = JsonPath.read(json(mvc.perform(complete(e, token, phone, consent))
        .andExpect(status().isOk()).andReturn()), "$.data.pairingId");
    assertTrue(codes(shop).isEmpty(), "the used code is deleted (R9.11)");

    // the same phone sending the same complete again (a lost answer): refused, nothing new
    expectRefusal(complete(e, token, phone, consent), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    // a stranger who captured the token and brings their own keys: refused too
    Phone stranger = new Phone(phone.installationId(), p256(), p256());
    expectRefusal(complete(e, token, stranger, consent), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    expectRefusal(complete(e, token, Phone.create(), consent), ErrorCode.HR_PAIRING_CODE_EXPIRED);
    // and the code itself works once (R2.5): it matches nothing now
    expectRefusal(claim(e, code, installation()), ErrorCode.HR_PAIRING_CODE_WRONG);

    List<DevicePairing> pairings = it.template.find(
        Query.query(Criteria.where("shopId").is(shop)), DevicePairing.class);
    assertEquals(1, pairings.size(), "exactly one pairing came out of the handshake");
    DevicePairing p = pairings.get(0);
    assertEquals(pairingId, p.getId());
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(spki(phone.punchKey()), p.getPunchPublicKey(), "the keys were not swapped");
    assertEquals(spki(phone.deviceKey()), p.getDevicePublicKey());
    assertEquals(1, audits(shop, HrAuditAction.DEVICE_PAIRED).size());
    assertTrue(audits(shop, HrAuditAction.DEVICE_REPLACED).isEmpty());
    // the real phone is unaffected by the replays
    mvc.perform(signedMe(pairingId, phone)).andExpect(status().isOk());
    expectRefusal(signedMe(pairingId, stranger), ErrorCode.HR_APP_NOT_VERIFIED);
  }

  @Test
  void theEleventhAttemptOnOneCodeInvalidatesIt() throws Exception {
    String shop = shop("0c", true);
    Employee e = worker(shop, "EMP-001", "Mani");
    String code = createCode(shop, e);
    Phone owner = Phone.create();
    String token = JsonPath.read(claimOk(e, code, owner, "Redmi Note 11"), "$.data.claimToken");

    // nine more phones, each a different installation (so none is locked), read it as used
    for (int i = 2; i <= 10; i++) {
      expectRefusal(claim(e, code, installation()), ErrorCode.HR_PAIRING_CODE_USED);
      assertEquals(i, codes(shop).get(0).getAttempts());
    }
    assertEquals(PairingCodeStatus.CLAIMED, codes(shop).get(0).getStatus());

    expectRefusal(claim(e, code, installation()), ErrorCode.HR_TOO_MANY_ATTEMPTS);
    assertEquals(PairingCodeStatus.INVALIDATED, codes(shop).get(0).getStatus());
    assertEquals(11, codes(shop).get(0).getAttempts());
    // from now on the code is simply cancelled, to everyone: the phone that claimed it
    // legitimately can no longer complete, and a 12th guess is not "too many attempts"
    expectRefusal(complete(e, token, owner, 1), ErrorCode.HR_PAIRING_CODE_CANCELLED);
    expectRefusal(claim(e, code, installation()), ErrorCode.HR_PAIRING_CODE_CANCELLED);
    assertEquals(PairingCodeStatus.INVALIDATED, codes(shop).get(0).getStatus());
    assertEquals(0, it.template.count(Query.query(Criteria.where("shopId").is(shop)),
        DevicePairing.class));
  }

  @Test
  void fiveWrongCodesLockTheInstallationForFifteenMinutes() throws Exception {
    String shop = shop("0d", true);
    Employee e = worker(shop, "EMP-001", "Nila");
    String code = createCode(shop, e);
    String locked = installation();

    for (int i = 0; i < 5; i++) {
      // well-formed codes that match no document: counted against the installation only
      expectRefusal(claim(e, "XXXX" + (i + 2) + "XXX", locked), ErrorCode.HR_PAIRING_CODE_WRONG);
    }
    // the 6th, even with the right code, is refused with the minutes left
    mvc.perform(claim(e, code, locked))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error")
            .value("Too many wrong codes. Wait 15 minutes and try again."))
        .andExpect(jsonPath("$.data.status").value(429));
    assertEquals(0, codes(shop).get(0).getAttempts(), "a locked phone never reaches the code");
    // the right code from another phone is unaffected
    claimOk(e, code, Phone.create(), "Redmi Note 11");

    clock.advance(Duration.ofMinutes(14).plusSeconds(30));
    mvc.perform(claim(e, code, locked))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error")
            .value("Too many wrong codes. Wait 1 minute and try again."));
    clock.advance(Duration.ofSeconds(30));
    // unlocked: the attempt reaches the service again (and the code is now another phone's)
    String later = createCode(shop, e);
    claimOk(e, later, Phone.create(), "Redmi Note 11");
    expectRefusal(claim(e, later, locked), ErrorCode.HR_PAIRING_CODE_USED);
  }

  // ---- unpair ----------------------------------------------------------------------------------

  @Test
  void managerUnpairEndsThePairingAndTheNextSignedCallIsRefused() throws Exception {
    String shop = shop("0e", true);
    Employee e = worker(shop, "EMP-001", "Omar");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    mvc.perform(signedMe(pairingId, phone)).andExpect(status().isOk());
    clock.advance(Duration.ofMinutes(1));

    mvc.perform(asUser(post("/api/v1/hr/employees/" + e.getId() + "/pairing/unpair"), MANAGER,
            shop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("NOT_PAIRED"))
        .andExpect(jsonPath("$.data.phoneModel").doesNotExist());

    DevicePairing p = pairing(pairingId);
    assertEquals(PairingStatus.UNPAIRED, p.getStatus());
    assertEquals(UnpairReason.MANUAL, p.getUnpairReason());
    assertEquals(MANAGER, p.getUnpairedBy());
    assertEquals(clock.instant(), p.getUnpairedAt());
    assertEquals(clock.instant().plus(Duration.ofDays(90)), p.getPurgeAt());
    assertNotNull(p.getDevicePublicKey(), "the row is kept 90 days for the audit trail");
    List<HrAuditEvent> unpaired = audits(shop, HrAuditAction.DEVICE_UNPAIRED);
    assertEquals(1, unpaired.size());
    assertEquals(MANAGER, unpaired.get(0).getActorUserId());
    assertEquals(UnpairReason.MANUAL.name(), unpaired.get(0).getReason());
    assertEquals("Redmi Note 11", unpaired.get(0).getBefore().get("phoneModel"));

    expectRefusal(signedMe(pairingId, phone), ErrorCode.HR_APP_NOT_PAIRED);
    // unpairing again changes nothing and writes no second audit row
    mvc.perform(asUser(post("/api/v1/hr/employees/" + e.getId() + "/pairing/unpair"), MANAGER,
        shop)).andExpect(status().isOk());
    assertEquals(1, audits(shop, HrAuditAction.DEVICE_UNPAIRED).size());
    assertEquals(clock.instant(), pairing(pairingId).getUnpairedAt());
    // and the employee can be paired again
    Phone next = Phone.create();
    String again = pair(shop, e, next, "Redmi Note 12");
    mvc.perform(signedMe(again, next)).andExpect(status().isOk());
  }

  @Test
  void anExitDateInTheFutureSchedulesTheUnpairAndTheJobDoesIt() throws Exception {
    String shop = shop("0f", true);
    Employee e = worker(shop, "EMP-001", "Priya");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    LocalDate tomorrow = HrDateUtils.today().plusDays(1);
    Instant due = tomorrow.atStartOfDay(HrDateUtils.ZONE).toInstant();

    mvc.perform(asUser(post("/api/v1/hr/employees/" + e.getId() + "/exit"), MANAGER, shop)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"exitDate\":\"" + HrDateUtils.format(tomorrow) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value(EmployeeStatus.EXITED.name()));

    // still paired until 00:00 Asia/Kolkata of the exit date (R3.5)
    DevicePairing p = pairing(pairingId);
    assertEquals(PairingStatus.ACTIVE, p.getStatus());
    assertEquals(due, p.getScheduledUnpairAt());
    mvc.perform(signedMe(pairingId, phone)).andExpect(status().isOk());
    assertEquals(0, exitJob.unpairDue(due.minusSeconds(1)), "not due yet");
    assertEquals(PairingStatus.ACTIVE, pairing(pairingId).getStatus());

    assertEquals(1, exitJob.unpairDue(due));
    DevicePairing gone = pairing(pairingId);
    assertEquals(PairingStatus.UNPAIRED, gone.getStatus());
    assertEquals(UnpairReason.EMPLOYEE_EXITED, gone.getUnpairReason());
    assertEquals(DevicePairingService.SYSTEM, gone.getUnpairedBy());
    assertNull(gone.getScheduledUnpairAt());
    List<HrAuditEvent> unpaired = audits(shop, HrAuditAction.DEVICE_UNPAIRED);
    assertEquals(1, unpaired.size());
    assertEquals(DevicePairingService.SYSTEM, unpaired.get(0).getActorUserId());
    assertEquals(UnpairReason.EMPLOYEE_EXITED.name(), unpaired.get(0).getReason());
    assertEquals(0, exitJob.unpairDue(due), "idempotent");
    expectRefusal(signedMe(pairingId, phone), ErrorCode.HR_APP_NOT_PAIRED);
  }

  @Test
  void anExitDateThatHasArrivedUnpairsAtOnce() throws Exception {
    String shop = shop("10", true);
    Employee e = worker(shop, "EMP-001", "Qadir");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");

    mvc.perform(asUser(post("/api/v1/hr/employees/" + e.getId() + "/exit"), MANAGER, shop)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"exitDate\":\"" + HrDateUtils.format(HrDateUtils.today()) + "\"}"))
        .andExpect(status().isOk());

    DevicePairing p = pairing(pairingId);
    assertEquals(PairingStatus.UNPAIRED, p.getStatus());
    assertEquals(UnpairReason.EMPLOYEE_EXITED, p.getUnpairReason());
    assertEquals(DevicePairingService.SYSTEM, p.getUnpairedBy());
    expectRefusal(signedMe(pairingId, phone), ErrorCode.HR_APP_NOT_PAIRED);
    assertEquals(0, exitJob.unpairDue(clock.instant().plus(Duration.ofDays(2))),
        "nothing left to do");
  }

  // ---- punches (task 5.10, IT part): the signed path end to end -------------------------------

  @Test
  void anOnlinePunchIsStoredWithServerTimeAndDerivesAnAppDay() throws Exception {
    String shop = shop("11", true);
    Employee e = worker(shop, "EMP-001", "Rani");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    clock.advance(Duration.ofMinutes(1));
    Instant t0 = clock.instant();

    // no Bearer anywhere on this path; the phone's clock is 40 s slow, and online the server's
    // time is the punch's (R4.4)
    Draft first = new Draft(pairingId, shop, e.getId(), nonce(), t0.minusSeconds(40), false,
        false, null, null, false);
    mvc.perform(signedPunch(pairingId, phone, first.body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(header().exists(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.direction").value("IN"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.atLocal").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayIn").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayOut").doesNotExist())
        .andExpect(jsonPath("$.data.nextDirection").value("OUT"))
        .andExpect(jsonPath("$.data.flags").isEmpty());

    List<Punch> stored = punches(shop);
    assertEquals(1, stored.size());
    Punch p = stored.get(0);
    assertEquals(PunchSource.BIOMETRIC_APP, p.getSource());
    assertEquals(t0, p.getAt(), "server time, not the phone's");
    assertEquals(t0.minusSeconds(40), p.getPhoneTime());
    assertEquals(dayOf(t0), p.getLocalDate());
    assertEquals(PunchDirection.IN, p.getDirection());
    assertEquals(pairingId, p.getPairingId());
    assertEquals("Redmi Note 11", p.getDeviceModel());
    assertEquals(first.dedupeKey(), p.getDedupeKey());
    assertEquals(first.payloadSha256(), p.getPayloadSha256());
    assertNull(p.getDistanceMetres());
    assertTrue(p.getFlags().isEmpty());
    String raw = rawJson("hr_punches", shop);
    assertFalse(raw.contains("\"lat\"") || raw.contains("\"lng\""), "no coordinates (R9.2)");

    // the day is derived from the app punch and says so (R8.1)
    AttendanceDay d = day(shop, e, dayOf(t0));
    assertEquals(AttendanceSource.BIOMETRIC_APP, d.getSource());
    assertEquals(AttendanceStatus.PRESENT, d.getStatus());
    assertEquals(hhmm(t0), d.getInTime());
    assertNull(d.getOutTime());
    assertEquals(1, d.getPunchCount());
    assertNull(d.getMarkedByUserId());
    assertEquals(t0, pairing(pairingId).getLastPunchAt());
    mvc.perform(asUser(get("/api/v1/hr/employees/" + e.getId() + "/pairing"), MANAGER, shop))
        .andExpect(jsonPath("$.data.state").value("PAIRED"))
        .andExpect(jsonPath("$.data.lastPunchAt").exists());

    // a second tap a minute later is stored but does not count (R4.8)
    clock.advance(Duration.ofMinutes(1));
    Draft second = Draft.online(pairingId, shop, e);
    mvc.perform(signedPunch(pairingId, phone, second.body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"))
        .andExpect(jsonPath("$.data.duplicate").value(true))
        .andExpect(jsonPath("$.data.todayIn").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayOut").doesNotExist())
        .andExpect(jsonPath("$.data.nextDirection").value("OUT"));
    assertEquals(2, punches(shop).size());
    assertEquals(1, day(shop, e, dayOf(t0)).getPunchCount());

    // five more minutes on, the tap is the check-out
    clock.advance(Duration.ofMinutes(5));
    Instant t1 = clock.instant();
    Draft third = Draft.online(pairingId, shop, e);
    mvc.perform(signedPunch(pairingId, phone, third.body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.direction").value("OUT"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.todayIn").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayOut").value(hhmm(t1)));
    d = day(shop, e, dayOf(t0));
    assertEquals(2, d.getPunchCount());
    assertEquals(hhmm(t1), d.getOutTime());
    assertEquals(AttendanceSource.BIOMETRIC_APP, d.getSource());
    assertEquals(t1, pairing(pairingId).getLastPunchAt());
    mvc.perform(signedMe(pairingId, phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.todayIn").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayOut").value(hhmm(t1)))
        .andExpect(jsonPath("$.data.nextDirection").value("OUT"));
  }

  @Test
  void replayingAnAcceptedPunchAnswersTheStoredResultAndStoresNothing() throws Exception {
    String shop = shop("12", true);
    Employee e = worker(shop, "EMP-001", "Sita");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    Instant t0 = clock.instant();
    Draft draft = Draft.online(pairingId, shop, e);
    String body = draft.body(phone.punchKey());
    mvc.perform(signedPunch(pairingId, phone, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));

    // the answer was lost; the app resends the very same body in a new envelope (R5.10)
    clock.advance(Duration.ofMinutes(7));
    mvc.perform(signedPunch(pairingId, phone, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("REPLAYED"))
        .andExpect(jsonPath("$.data.direction").value("IN"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.atLocal").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayIn").value(hhmm(t0)))
        .andExpect(jsonPath("$.data.todayOut").doesNotExist())
        .andExpect(jsonPath("$.data.nextDirection").value("OUT"));
    List<Punch> stored = punches(shop);
    assertEquals(1, stored.size(), "nothing new stored");
    assertEquals(t0, stored.get(0).getAt());
    assertEquals(draft.payloadSha256(), stored.get(0).getPayloadSha256());
    assertEquals(1, day(shop, e, dayOf(t0)).getPunchCount());
    assertEquals(t0, pairing(pairingId).getLastPunchAt(), "a replay is not a punch");

    // the same punch id with different content, correctly signed: tampering (R5.11)
    Draft changed = new Draft(pairingId, shop, e.getId(), draft.punchId(),
        draft.phoneTime().plusSeconds(1), false, false, null, null, false);
    expectRefusal(signedPunch(pairingId, phone, changed.body(phone.punchKey())),
        ErrorCode.HR_APP_NOT_VERIFIED);
    assertEquals(1, punches(shop).size());
    assertEquals(draft.payloadSha256(), punches(shop).get(0).getPayloadSha256());
    assertEquals(t0, pairing(pairingId).getLastPunchAt());
  }

  @Test
  void aBadEnvelopeOrPunchSignatureIsRefusedAndNothingIsStored() throws Exception {
    String shop = shop("13", true);
    Employee e = worker(shop, "EMP-001", "Tara");
    Phone phone = Phone.create();
    Phone stranger = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    long now = clock.instant().getEpochSecond();
    Draft draft = Draft.online(pairingId, shop, e);
    String body = draft.body(phone.punchKey());
    MockHttpServletRequestBuilder punchPost = post(ROOT + "/punches");

    // the envelope (R9.8): stale or future timestamp, another phone's device key, tampered body
    MvcResult stale = mvc.perform(signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"),
            null, body, now - 11 * 60, nonce()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(header().exists(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME))
        .andExpect(jsonPath("$.error").value(ErrorCode.HR_APP_NOT_VERIFIED.getMessage()))
        .andReturn();
    assertEquals(Long.toString(now),
        stale.getResponse().getHeader(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME),
        "the app corrects its envelope clock from this");
    expectRefusal(signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"), null, body,
        now + 11 * 60, nonce()), ErrorCode.HR_APP_NOT_VERIFIED);
    expectRefusal(signed(pairingId, stranger.deviceKey(), post(ROOT + "/punches"), null, body,
        now, nonce()), ErrorCode.HR_APP_NOT_VERIFIED);
    String tampered = body.replace("\"mock\":false", "\"mock\":true");
    assertFalse(tampered.equals(body));
    expectRefusal(signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"), null, body, now,
        nonce()).content(tampered), ErrorCode.HR_APP_NOT_VERIFIED);
    // no envelope at all, or an unknown pairing: not paired (R3.2); still no Bearer involved
    expectRefusal(punchPost.contentType(MediaType.APPLICATION_JSON).content(body),
        ErrorCode.HR_APP_NOT_PAIRED);
    expectRefusal(signed("66cc00000000000000000001", phone.deviceKey(), post(ROOT + "/punches"),
        null, body), ErrorCode.HR_APP_NOT_PAIRED);

    // a good envelope around a punch the punch key did not sign (R5.7)
    expectRefusal(signedPunch(pairingId, phone, draft.body(phone.deviceKey())),
        ErrorCode.HR_APP_NOT_VERIFIED);
    expectRefusal(signedPunch(pairingId, phone, draft.body(stranger.punchKey())),
        ErrorCode.HR_APP_NOT_VERIFIED);
    // ... or a punch about someone else, signed with the right key
    Draft other = new Draft(pairingId, shop, "66ee00000000000000000001", draft.punchId(),
        draft.phoneTime(), false, false, null, null, false);
    expectRefusal(signedPunch(pairingId, phone, other.body(phone.punchKey())),
        ErrorCode.HR_APP_NOT_VERIFIED);
    assertTrue(punches(shop).isEmpty(), "every refusal stored nothing");
    assertNull(day(shop, e, today()));
    assertNull(pairing(pairingId).getLastPunchAt());
    assertTrue(ErrorCode.HR_APP_NOT_VERIFIED.getMessage()
        .endsWith("Ask your manager to mark your attendance."), "R7.10");

    // a good one, then its nonce again: with another body, and with the identical request
    String nonce = nonce();
    mvc.perform(signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"), null, body, now,
            nonce))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));
    expectRefusal(signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"), null,
        Draft.online(pairingId, shop, e).body(phone.punchKey()), now, nonce),
        ErrorCode.HR_APP_NOT_VERIFIED);
    expectRefusal(signed(pairingId, phone.deviceKey(), post(ROOT + "/punches"), null, body, now,
        nonce), ErrorCode.HR_APP_NOT_VERIFIED);
    assertEquals(1, punches(shop).size());
    assertEquals(1, day(shop, e, today()).getPunchCount());
  }

  @Test
  void anOfflinePunchKeepsThePhoneTimeWithinTheWindows() throws Exception {
    String shop = shop("14", true);
    Employee e = worker(shop, "EMP-001", "Uma");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    Instant now = clock.instant();
    Instant twoHoursAgo = now.minus(Duration.ofHours(2));

    Draft offline = Draft.online(pairingId, shop, e).offlineAt(twoHoursAgo);
    mvc.perform(signedPunch(pairingId, phone, offline.body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.direction").value("IN"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.atLocal").value(hhmm(twoHoursAgo)))
        .andExpect(jsonPath("$.data.flags.length()").value(1))
        .andExpect(jsonPath("$.data.flags[0]").value("RECORDED_OFFLINE"));
    Punch p = punches(shop).get(0);
    assertEquals(twoHoursAgo, p.getAt(), "the phone's time is the punch's (R6.9)");
    assertEquals(twoHoursAgo, p.getPhoneTime());
    assertEquals(dayOf(twoHoursAgo), p.getLocalDate());
    assertEquals(List.of(PunchFlag.RECORDED_OFFLINE), p.getFlags());
    assertEquals(now, p.getCreatedAt(), "when it arrived");
    AttendanceDay d = day(shop, e, dayOf(twoHoursAgo));
    assertEquals(AttendanceSource.BIOMETRIC_APP, d.getSource());
    assertEquals(hhmm(twoHoursAgo), d.getInTime());
    assertEquals(twoHoursAgo, pairing(pairingId).getLastPunchAt());
    // older than an hour when it arrived: one tick on the backlog panel (R10.7)
    verify(it.metrics, atLeastOnce()).increment(eq(HrMetricsConstants.APP_OFFLINE_BACKLOG_TOTAL),
        eq(1.0), eq("module"), eq(HrMetricsConstants.MODULE), eq(HrMetricsConstants.TAG_SHOP),
        eq(shop));

    // 25 hours old: too late; 6 minutes ahead: the phone's clock is wrong (R6.11, R6.12)
    expectRefusal(signedPunch(pairingId, phone, Draft.online(pairingId, shop, e)
        .offlineAt(now.minus(Duration.ofHours(25))).body(phone.punchKey())),
        ErrorCode.HR_APP_TOO_LATE);
    expectRefusal(signedPunch(pairingId, phone, Draft.online(pairingId, shop, e)
        .offlineAt(now.plus(Duration.ofMinutes(6))).body(phone.punchKey())),
        ErrorCode.HR_APP_CLOCK_WRONG);
    assertEquals(1, punches(shop).size());
    assertEquals(twoHoursAgo, pairing(pairingId).getLastPunchAt());
    assertTrue(ErrorCode.HR_APP_TOO_LATE.getMessage()
        .endsWith("Ask your manager to mark your attendance."), "R7.10");
  }

  @Test
  void withTheSwitchOffThePunchIsRefusedAndThePairingSurvives() throws Exception {
    String shop = shop("15", true);
    Employee e = worker(shop, "EMP-001", "Vani");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");

    biometricApp(shop, false);
    // the switch is read per punch (R1.3); the refusal ends with the R7.10 sentence
    MvcResult r = mvc.perform(signedPunch(pairingId, phone,
            Draft.online(pairingId, shop, e).body(phone.punchKey())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(ErrorCode.HR_APP_OFF.getMessage()))
        .andReturn();
    String message = JsonPath.read(json(r), "$.error");
    assertTrue(message.endsWith("Ask your manager to mark your attendance."), message);
    assertTrue(punches(shop).isEmpty());
    assertEquals(PairingStatus.ACTIVE, pairing(pairingId).getStatus(),
        "turning the switch off does not unpair (R1.5)");
    mvc.perform(signedMe(pairingId, phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.biometricApp").value(false))
        .andExpect(jsonPath("$.data.canPunch").value(false))
        .andExpect(jsonPath("$.data.reason").value(ErrorCode.HR_APP_OFF.getMessage()));

    // back on: the same phone punches without re-pairing
    biometricApp(shop, true);
    mvc.perform(signedPunch(pairingId, phone,
            Draft.online(pairingId, shop, e).body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));
    assertEquals(1, punches(shop).size());
  }

  @Test
  void aMockLocationIsBlockedOrFlaggedByTheOutsideAction() throws Exception {
    String blocking = shop("16", locationChecked(OutsideAction.BLOCK));
    Employee e = worker(blocking, "EMP-001", "Wafa");
    Phone phone = Phone.create();
    String pairingId = pair(blocking, e, phone, "Redmi Note 11");

    // Block (R4.6): a mock reading is refused before anything else about the location
    expectRefusal(signedPunch(pairingId, phone, Draft.online(pairingId, blocking, e)
        .located(20, 10, true).body(phone.punchKey())), ErrorCode.HR_FAKE_LOCATION);
    // and the My HR rule applies to the phone-computed numbers (R4.5)
    expectRefusal(signedPunch(pairingId, phone, Draft.online(pairingId, blocking, e)
        .located(150, 10, false).body(phone.punchKey())), ErrorCode.HR_OUTSIDE_SHOP);
    expectRefusal(signedPunch(pairingId, phone, Draft.online(pairingId, blocking, e)
        .located(null, null, false).body(phone.punchKey())), ErrorCode.HR_OUTSIDE_SHOP);
    expectRefusal(signedPunch(pairingId, phone, Draft.online(pairingId, blocking, e)
        .located(20, 250, false).body(phone.punchKey())), ErrorCode.HR_LOCATION_UNCLEAR);
    assertTrue(punches(blocking).isEmpty());
    mvc.perform(signedPunch(pairingId, phone, Draft.online(pairingId, blocking, e)
            .located(20, 10, false).body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.flags").isEmpty());
    Punch near = punches(blocking).get(0);
    assertEquals(20, near.getDistanceMetres());
    assertEquals(10, near.getAccuracyMetres());

    // Allow-and-flag: stored with the one flag and no distance
    String flagging = shop("17", locationChecked(OutsideAction.FLAG));
    Employee f = worker(flagging, "EMP-001", "Xavi");
    Phone second = Phone.create();
    String flaggedPairing = pair(flagging, f, second, "Pixel 7a");
    mvc.perform(signedPunch(flaggedPairing, second, Draft.online(flaggedPairing, flagging, f)
            .located(20, 10, true).body(second.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.flags.length()").value(1))
        .andExpect(jsonPath("$.data.flags[0]").value("FAKE_LOCATION"));
    Punch faked = punches(flagging).get(0);
    assertEquals(List.of(PunchFlag.FAKE_LOCATION), faked.getFlags());
    assertNull(faked.getDistanceMetres(), "the mock reading is not kept as a distance");
    assertNull(faked.getAccuracyMetres());
    assertEquals(AttendanceSource.BIOMETRIC_APP, day(flagging, f, today()).getSource());
  }

  @Test
  void aHandMadeMarkSurvivesALaterAppPunch() throws Exception {
    String shop = shop("18", true);
    Employee e = worker(shop, "EMP-001", "Yash");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    String date = dayOf(clock.instant());
    AttendanceDay manual = new AttendanceDay();
    manual.setShopId(shop);
    manual.setEmployeeId(e.getId());
    manual.setDate(date);
    manual.setPeriod(HrDateUtils.periodOf(date));
    manual.setStatus(AttendanceStatus.ABSENT);
    manual.setSource(AttendanceSource.MANUAL);
    manual.setMarkedAt(clock.instant());
    manual.setMarkedByUserId(MANAGER);
    it.template.insert(manual);

    mvc.perform(signedPunch(pairingId, phone,
            Draft.online(pairingId, shop, e).body(phone.punchKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));

    assertEquals(1, punches(shop).size(), "the punch is kept for the drawer");
    AttendanceDay d = day(shop, e, date);
    assertEquals(AttendanceStatus.ABSENT, d.getStatus(), "the hand-made mark wins (R7.9)");
    assertEquals(AttendanceSource.MANUAL, d.getSource());
    assertEquals(MANAGER, d.getMarkedByUserId());
    assertNull(d.getPunchCount());
    assertEquals(1, it.template.count(Query.query(Criteria.where("shopId").is(shop)),
        AttendanceDay.class));
    assertEquals(clock.instant(), pairing(pairingId).getLastPunchAt());
  }

  @Test
  void historyShowsTheCallersOwnPunchesNewestFirstWithinThirtyOneDays() throws Exception {
    String shop = shop("19", true);
    Employee a = worker(shop, "EMP-001", "Zoya");
    Employee b = worker(shop, "EMP-002", "Arun");
    Phone phoneA = Phone.create();
    Phone phoneB = Phone.create();
    String pairingA = pair(shop, a, phoneA, "Redmi Note 11");
    String pairingB = pair(shop, b, phoneB, "Galaxy A14");
    LocalDate todayDate = clock.instant().atZone(HrDateUtils.ZONE).toLocalDate();
    String todayText = HrDateUtils.format(todayDate);
    Instant nine = todayDate.atStartOfDay(HrDateUtils.ZONE).toInstant().plusSeconds(9 * 3600);

    storedPunch(shop, a, pairingA, nine.minus(Duration.ofDays(30))); // the oldest day shown
    storedPunch(shop, a, pairingA, nine.minus(Duration.ofDays(31))); // just outside
    storedPunch(shop, a, pairingA, nine.minus(Duration.ofDays(40)));
    storedPunch(shop, a, pairingA, nine.minus(Duration.ofDays(3)));
    storedPunch(shop, a, pairingA, nine.minus(Duration.ofDays(3)).plusSeconds(120)); // duplicate
    storedPunch(shop, b, pairingB, nine.minus(Duration.ofDays(3)));
    mvc.perform(signedPunch(pairingA, phoneA,
            Draft.online(pairingA, shop, a).body(phoneA.punchKey())))
        .andExpect(status().isOk());

    MvcResult r = mvc.perform(signedHistory(pairingA, phoneA, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.length()").value(4))
        .andReturn();
    List<String> owners = JsonPath.read(json(r), "$.data[*].employeeId");
    assertEquals(Set.of(a.getId()), new HashSet<>(owners), "own punches only (R8.9)");
    List<String> dates = JsonPath.read(json(r), "$.data[*].localDate");
    String threeDaysAgo = HrDateUtils.format(todayDate.minusDays(3));
    assertEquals(List.of(todayText, threeDaysAgo, threeDaysAgo,
        HrDateUtils.format(todayDate.minusDays(30))), dates, "newest first");
    List<Boolean> counted = JsonPath.read(json(r), "$.data[*].counted");
    assertEquals(List.of(true, false, true, true), counted, "the 5-minute rule per day");
    assertEquals("BIOMETRIC_APP", JsonPath.read(json(r), "$.data[0].source"));
    assertEquals("Redmi Note 11", JsonPath.read(json(r), "$.data[0].deviceModel"));
    assertEquals("IN", JsonPath.read(json(r), "$.data[0].direction"));

    // 31 days is the cap (R4.10): an explicit 31-day range works, 32 days, a reversed range
    // and a malformed date are 400s; an older window is fine
    String from31 = HrDateUtils.format(todayDate.minusDays(30));
    mvc.perform(signedHistory(pairingA, phoneA, "from=" + from31 + "&to=" + todayText))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4));
    mvc.perform(signedHistory(pairingA, phoneA,
            "from=" + HrDateUtils.format(todayDate.minusDays(31)) + "&to=" + todayText))
        .andExpect(status().isBadRequest());
    mvc.perform(signedHistory(pairingA, phoneA, "from=" + todayText + "&to=" + from31))
        .andExpect(status().isBadRequest());
    mvc.perform(signedHistory(pairingA, phoneA, "from=yesterday"))
        .andExpect(status().isBadRequest());
    mvc.perform(signedHistory(pairingA, phoneA,
            "from=" + HrDateUtils.format(todayDate.minusDays(40))
                + "&to=" + HrDateUtils.format(todayDate.minusDays(31))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
    // the other phone sees only its own employee's punches
    mvc.perform(signedHistory(pairingB, phoneB, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].employeeId").value(b.getId()))
        .andExpect(jsonPath("$.data[0].deviceModel").value("Redmi Note 11"));
  }

  /**
   * Worker-app D11, DD4: the unsigned handshake and the pairing proof are gone. The removed paths
   * are no routes at all (404), and a request to them reads no code, counts nothing on any code,
   * creates nothing and leaves the device interceptor's signature-failure count alone.
   */
  @Test
  void theUnsignedHandshakeAndTheProofNoLongerExist() throws Exception {
    String shop = shop("1d", true);
    Employee e = worker(shop, "EMP-001", "Bina");
    Phone phone = Phone.create();
    String code = createCode(shop, e);
    String pairingId;
    long failedBefore = signatureFailures();

    // the old device-path handshake, unsigned, exactly as the login-less app sent it
    mvc.perform(post(ROOT + "/pairings/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody(code, phone.installationId(), "Redmi Note 11")))
        .andExpect(status().isNotFound());
    mvc.perform(post(ROOT + "/pairings/complete").contentType(MediaType.APPLICATION_JSON)
            .content("{\"claimToken\":\"x\",\"installationId\":\"" + phone.installationId()
                + "\",\"punchPublicKey\":\"k\",\"devicePublicKey\":\"k\",\"consentVersion\":1}"))
        .andExpect(status().isNotFound());
    mvc.perform(post(ROOT + "/pairings/abandon").contentType(MediaType.APPLICATION_JSON)
            .content("{\"claimToken\":\"x\"}"))
        .andExpect(status().isNotFound());
    assertEquals(PairingCodeStatus.UNUSED, codes(shop).get(0).getStatus(), "the code is untouched");
    assertEquals(0, codes(shop).get(0).getAttempts(), "nothing counted on the code");
    assertEquals(0, it.template.count(Query.query(Criteria.where("shopId").is(shop)),
        DevicePairing.class), "no pairing");

    // the same code still pairs on the only handshake there is
    String claimed = claimOk(e, code, phone, "Redmi Note 11");
    pairingId = JsonPath.read(json(mvc.perform(complete(e, JsonPath.read(claimed,
            "$.data.claimToken"), phone, JsonPath.read(claimed, "$.data.consent.version")))
        .andExpect(status().isOk()).andReturn()), "$.data.pairingId");
    assertTrue(codes(shop).isEmpty(), "the used code is deleted");

    // the proof: neither signed nor unsigned
    mvc.perform(signedPost(pairingId, phone, "/pairings/me/proof", null))
        .andExpect(status().isNotFound());
    mvc.perform(post(ROOT + "/pairings/me/proof")).andExpect(status().isNotFound());
    assertFalse(it.template.collectionExists("hr_pairing_proofs")
        && it.template.getCollection("hr_pairing_proofs").countDocuments() > 0,
        "no proof is ever written");
    assertEquals(failedBefore, signatureFailures(),
        "the removed routes are not device calls: no signature failure counted");
    // the real phone is unaffected
    mvc.perform(signedMe(pairingId, phone)).andExpect(status().isOk());
  }

  /** How often {@code inventory_hr_app_punches_total{result=signature_failed}} was incremented. */
  private static long signatureFailures() {
    return mockingDetails(it.metrics).getInvocations().stream()
        .filter(i -> "increment".equals(i.getMethod().getName())
            && HrMetricsConstants.APP_PUNCHES_TOTAL.equals(i.getArgument(0))
            && Arrays.asList(i.getArguments())
                .contains(HrMetricsConstants.AppPunchResult.SIGNATURE_FAILED))
        .count();
  }

  @Test
  void theWorkerRecordsConsentAndUnpairsAndTheNextCallIsRefused() throws Exception {
    String shop = shop("1a", true);
    Employee e = worker(shop, "EMP-001", "Bina");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");
    int latest = new ConsentVersions().latest().version();
    clock.advance(Duration.ofMinutes(1));

    // "I agree" after pairing (R9.13)
    mvc.perform(signedPost(pairingId, phone, "/consent", "{\"version\":" + latest + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agreedVersion").value(latest));
    DevicePairing p = pairing(pairingId);
    assertEquals(latest, p.getConsentVersion());
    assertEquals(2, p.getConsents().size());
    assertEquals(clock.instant(), p.getConsentAt());
    List<HrAuditEvent> consented = audits(shop, HrAuditAction.DEVICE_CONSENT_UPDATED);
    assertEquals(1, consented.size());
    assertEquals(DevicePairingService.SELF, consented.get(0).getActorUserId());
    mvc.perform(signedPost(pairingId, phone, "/consent", "{\"version\":99}"))
        .andExpect(status().isBadRequest());
    mvc.perform(signedPost(pairingId, phone, "/consent", "{}"))
        .andExpect(status().isBadRequest());
    assertEquals(2, pairing(pairingId).getConsents().size());

    // "Remove this phone" (R3.7, R3.9): within the request, reason WORKER, actor self
    mvc.perform(signedPost(pairingId, phone, "/pairings/me/unpair", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unpaired").value(true));
    DevicePairing gone = pairing(pairingId);
    assertEquals(PairingStatus.UNPAIRED, gone.getStatus());
    assertEquals(UnpairReason.WORKER, gone.getUnpairReason());
    assertEquals(DevicePairingService.SELF, gone.getUnpairedBy());
    assertEquals(clock.instant(), gone.getUnpairedAt());
    assertEquals(clock.instant().plus(Duration.ofDays(90)), gone.getPurgeAt());
    List<HrAuditEvent> unpaired = audits(shop, HrAuditAction.DEVICE_UNPAIRED);
    assertEquals(1, unpaired.size());
    assertEquals(DevicePairingService.SELF, unpaired.get(0).getActorUserId());
    assertEquals(UnpairReason.WORKER.name(), unpaired.get(0).getReason());
    assertEquals("Redmi Note 11", unpaired.get(0).getBefore().get("phoneModel"));

    // whatever the phone calls next: 403, and it wipes itself (R3.2); a punch stores nothing
    expectRefusal(signedMe(pairingId, phone), ErrorCode.HR_APP_NOT_PAIRED);
    expectRefusal(signedPunch(pairingId, phone,
        Draft.online(pairingId, shop, e).body(phone.punchKey())), ErrorCode.HR_APP_NOT_PAIRED);
    expectRefusal(signedPost(pairingId, phone, "/pairings/me/unpair", null),
        ErrorCode.HR_APP_NOT_PAIRED);
    expectRefusal(signedPost(pairingId, phone, "/consent", "{\"version\":" + latest + "}"),
        ErrorCode.HR_APP_NOT_PAIRED);
    assertTrue(punches(shop).isEmpty());
    assertEquals(1, audits(shop, HrAuditAction.DEVICE_UNPAIRED).size());
    mvc.perform(asUser(get("/api/v1/hr/employees/" + e.getId() + "/pairing"), MANAGER, shop))
        .andExpect(jsonPath("$.data.state").value("NOT_PAIRED"));
  }

  @Test
  void theHealthReadStaysOnTheBearerChainAndNeedsViewAttendance() throws Exception {
    String shop = shop("1b", true);
    Employee e = worker(shop, "EMP-001", "Chand");
    Phone phone = Phone.create();
    String pairingId = pair(shop, e, phone, "Redmi Note 11");

    mvc.perform(asUser(get(ROOT + "/health"), MANAGER, shop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.alerts").isArray())
        .andExpect(jsonPath("$.data.alerts.length()").value(0));
    it.template.insert(new AppHealthAlert(null, shop, 0.05, 2, 40, clock.instant(), null));
    mvc.perform(asUser(get(ROOT + "/health"), MANAGER, shop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.alerts.length()").value(1))
        .andExpect(jsonPath("$.data.alerts[0].shopId").value(shop))
        .andExpect(jsonPath("$.data.alerts[0].failed").value(2))
        .andExpect(jsonPath("$.data.alerts[0].attempts").value(40));
    // VIEW_ATTENDANCE is required; without a Bearer context it is a 401, and a phone's envelope
    // is worth nothing here because the device interceptor skips /health
    mvc.perform(asUser(get(ROOT + "/health"), VIEWER, shop))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("You do not have permission to do this in HR"));
    mvc.perform(get(ROOT + "/health")).andExpect(status().isUnauthorized());
    mvc.perform(signed(pairingId, phone.deviceKey(), get(ROOT + "/health"), null, null))
        .andExpect(status().isUnauthorized());
  }

  private static String nonce() {
    return UUID.randomUUID().toString();
  }

  /** HH:mm of {@code at} in Asia/Kolkata, as the register and the app show times. */
  private static String hhmm(Instant at) {
    return at.atZone(HrDateUtils.ZONE).toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"));
  }

  /** One clock for the pairing service, the rate limiter and the interceptor; tests advance it. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
