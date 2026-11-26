package com.inventory.hr.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.HrNotificationResponse;
import com.inventory.hr.rest.dto.response.JoinRequestSelfResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.ShopLookupResponse;
import com.inventory.hr.rest.dto.response.WorkerProfileResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse.State;
import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.WorkerCredentialInterceptor;
import com.inventory.hr.service.WorkerJoinService;
import com.inventory.hr.service.WorkerPairingService;
import com.inventory.hr.service.WorkerSelfService;
import com.inventory.hr.service.WorkerStateService;
import com.inventory.hr.validation.JoinRequestInboxValidator;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The worker endpoints through MVC (worker-app R3.1–R3.6, R5.3, R5.6, R7.2, R7.9, R8.1, R8.2,
 * R8.4, R9.1, R10.1): every route reaches its service with the caller from the request
 * attribute and never from the request itself, the {@code ApiResponse} envelope, 400s before the
 * service is reached, the two-credentials refusal before any handler (the interceptor {@code
 * CorsConfig} registers on this prefix is part of the standalone setup here), {@code HR_WORKER_ONLY}
 * for a staff account without an employee on everything but {@code GET /me}, and the services'
 * refusals passing through with their code and text. The services are mocks; the rules live in
 * their own tests.
 */
@ExtendWith(MockitoExtension.class)
class WorkerControllerTest {

  private static final String BASE = "/api/v1/hr/worker";
  private static final String USER_ID = "66f1a2b3c4d5e6f7a8b9c0a1";
  private static final String SHOP_ID = "66f1a2b3c4d5e6f7a8b9c0d2";
  private static final String REQUEST_ID = "66f1a2b3c4d5e6f7a8b9c0e1";
  private static final String EMPLOYEE_ID = "66f1a2b3c4d5e6f7a8b9c0d3";
  private static final String NOTIFICATION_ID = "66f1a2b3c4d5e6f7a8b9c0f1";
  private static final String INSTALLATION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
  private static final Instant NOW = Instant.parse("2026-05-12T09:12:00Z");

  @Mock private WorkerStateService stateService;
  @Mock private WorkerJoinService joinService;
  @Mock private WorkerPairingService pairingService;
  @Mock private WorkerSelfService selfService;
  @Mock private MetricsWrapper metrics;

  private AppRateLimiter rateLimiter;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    rateLimiter = new AppRateLimiter(metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    WorkerController controller = new WorkerController();
    ReflectionTestUtils.setField(controller, "stateService", stateService);
    ReflectionTestUtils.setField(controller, "joinService", joinService);
    ReflectionTestUtils.setField(controller, "pairingService", pairingService);
    ReflectionTestUtils.setField(controller, "selfService", selfService);
    ReflectionTestUtils.setField(controller, "rateLimiter", rateLimiter);
    ReflectionTestUtils.setField(controller, "validator", new JoinRequestInboxValidator());
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(handler)
        .addMappedInterceptors(new String[] {WorkerCredentialInterceptor.PATH_PATTERN},
            new WorkerCredentialInterceptor())
        .build();
  }

  /** What {@code AuthenticationInterceptor} leaves on a Bearer request. */
  private static MockHttpServletRequestBuilder asWorker(MockHttpServletRequestBuilder request) {
    return request.requestAttr("userId", USER_ID).requestAttr("shopId", SHOP_ID);
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
      String body) {
    return asWorker(request).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static AccountView workerAccount() {
    return new AccountView(USER_ID, "Priya Sharma", "priya@example.com", "9876543210", "WORKER",
        null, true);
  }

  private static JoinRequestSelfResponse pendingJoin() {
    return new JoinRequestSelfResponse(REQUEST_ID, "Sri Ganesh Stores", WorkerJoinStatus.PENDING,
        NOW);
  }

  private static BaseException workerOnly() {
    return new BaseException(ErrorCode.HR_WORKER_ONLY, ErrorCode.HR_WORKER_ONLY.getMessage());
  }

  private static String claimBody() {
    return "{\"code\":\"ABCD-1234\",\"installationId\":\"" + INSTALLATION_ID
        + "\",\"os\":\"ANDROID\",\"osVersion\":\"14\",\"phoneModel\":\"Pixel 7\","
        + "\"appVersion\":\"2.0.0\"}";
  }

  private static String completeBody() {
    return "{\"claimToken\":\"claim-1\",\"installationId\":\"" + INSTALLATION_ID
        + "\",\"punchPublicKey\":\"punch-key\",\"devicePublicKey\":\"device-key\","
        + "\"consentVersion\":1}";
  }

  // ---- GET /me ---------------------------------------------------------------------------------

  @Test
  void meAnswersTheStateFromTheTokenAndNeverAppliesTheStaffRule() throws Exception {
    WorkerStateResponse state = new WorkerStateResponse();
    state.setState(State.STAFF_NO_EMPLOYEE);
    state.setAccount(new WorkerStateResponse.Account(USER_ID, "Ramesh", "r@x.in", null, null,
        "MANAGER"));
    state.setModules(List.of());
    state.setNotifications(new WorkerStateResponse.Notifications(0, List.of()));
    state.setServerTime(NOW);
    when(stateService.state(USER_ID)).thenReturn(state);

    mvc.perform(asWorker(get(BASE + "/me")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.state").value("STAFF_NO_EMPLOYEE"))
        .andExpect(jsonPath("$.data.account.userId").value(USER_ID))
        .andExpect(jsonPath("$.data.modules.length()").value(0))
        .andExpect(jsonPath("$.data.notifications.unread").value(0))
        .andExpect(jsonPath("$.data.serverTime").exists());
    verifyNoInteractions(selfService);
  }

  @Test
  void meWithoutACallerIs401() throws Exception {
    mvc.perform(get(BASE + "/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("User not authenticated"));
    verifyNoInteractions(stateService, selfService);
  }

  // ---- R9.1: the two credentials never travel together -----------------------------------------

  @Test
  void aRequestCarryingAPairingIdIsRefusedBeforeAnyHandler() throws Exception {
    mvc.perform(asWorker(get(BASE + "/me")).header("X-Pairing-Id", SHOP_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error")
            .value("A request may carry a login or a phone signature, not both."))
        .andExpect(jsonPath("$.data.status").value(400));
    verifyNoInteractions(stateService, joinService, pairingService, selfService);
  }

  @Test
  void aRequestCarryingASignatureIsRefusedBeforeAnyHandler() throws Exception {
    mvc.perform(json(post(BASE + "/pairings/claim"), claimBody())
            .header("X-Request-Signature", "MEUCIQ..."))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error")
            .value("A request may carry a login or a phone signature, not both."));
    verifyNoInteractions(stateService, joinService, pairingService, selfService);
  }

  // ---- R5.6: a staff account without an employee ------------------------------------------------

  @Test
  void aStaffAccountWithoutAnEmployeeGetsWorkerOnlyOnEverythingButMe() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenThrow(workerOnly());
    String text = "This app is for workers. Ask your manager to link your login to your employee"
        + " record from the Employees page.";

    List<MockHttpServletRequestBuilder> calls = List.of(
        asWorker(get(BASE + "/shops").param("ownerEmail", "ramesh@example.com")),
        json(post(BASE + "/join-requests"), "{\"shopId\":\"" + SHOP_ID + "\"}"),
        asWorker(post(BASE + "/join-requests/" + REQUEST_ID + "/withdraw")),
        json(post(BASE + "/pairings/claim"), claimBody()),
        json(post(BASE + "/pairings/complete"), completeBody()),
        json(post(BASE + "/pairings/abandon"), "{\"claimToken\":\"c\"}"),
        asWorker(get(BASE + "/punches")),
        asWorker(get(BASE + "/profile")),
        json(put(BASE + "/profile"), "{\"name\":\"Priya\",\"phone\":\"9876543210\"}"),
        asWorker(post(BASE + "/leave")),
        asWorker(get(BASE + "/notifications")),
        asWorker(post(BASE + "/notifications/" + NOTIFICATION_ID + "/read")));
    for (MockHttpServletRequestBuilder call : calls) {
      mvc.perform(call)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value(text))
          .andExpect(jsonPath("$.data.status").value(403));
    }
    verify(selfService, org.mockito.Mockito.times(calls.size())).requireWorkerAccess(USER_ID);
    verifyNoInteractions(stateService, joinService, pairingService);
    verify(selfService, never()).profile(anyString());
    verify(selfService, never()).punches(anyString(), any(), any());
    verify(selfService, never()).leave(anyString());
    verify(selfService, never()).notifications(anyString());
  }

  // ---- shop lookup ----------------------------------------------------------------------------

  @Test
  void lookupShopsAnswersTheOwnersShops() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(joinService.lookupShops(USER_ID, "ramesh@example.com")).thenReturn(
        new ShopLookupResponse(List.of(new ShopLookupResponse.Shop(SHOP_ID, "Sri Ganesh Stores",
            "Ramesh"))));

    mvc.perform(asWorker(get(BASE + "/shops").param("ownerEmail", "ramesh@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.shops.length()").value(1))
        .andExpect(jsonPath("$.data.shops[0].shopId").value(SHOP_ID))
        .andExpect(jsonPath("$.data.shops[0].shopName").value("Sri Ganesh Stores"))
        .andExpect(jsonPath("$.data.shops[0].ownerFirstName").value("Ramesh"));
  }

  @Test
  void lookupShopsWithoutAnEmailIs400BeforeTheService() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());

    mvc.perform(asWorker(get(BASE + "/shops")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("ownerEmail is required"));
    mvc.perform(asWorker(get(BASE + "/shops").param("ownerEmail", "   ")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(joinService);
  }

  @Test
  void lookupShopsOverTheLimitIs429WithTheWorkerText() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(joinService.lookupShops(USER_ID, "ramesh@example.com")).thenThrow(
        new BaseException(ErrorCode.HR_TOO_MANY_ATTEMPTS,
            ErrorCode.HR_TOO_MANY_ATTEMPTS_WORKER_MESSAGE));

    mvc.perform(asWorker(get(BASE + "/shops").param("ownerEmail", "ramesh@example.com")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value("Too many attempts, try again later"));
  }

  // ---- join requests ---------------------------------------------------------------------------

  @Test
  void createJoinRequestPassesTheShopAndAnswersTheRequest() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(joinService.create(USER_ID, SHOP_ID)).thenReturn(pendingJoin());

    mvc.perform(json(post(BASE + "/join-requests"), "{\"shopId\":\" " + SHOP_ID + " \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(REQUEST_ID))
        .andExpect(jsonPath("$.data.kind").doesNotExist())
        .andExpect(jsonPath("$.data.shopName").value("Sri Ganesh Stores"))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.requestedAt").exists());
  }

  @Test
  void createJoinRequestWithoutAShopIs400BeforeTheService() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());

    mvc.perform(json(post(BASE + "/join-requests"), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("shopId is required"));
    mvc.perform(asWorker(post(BASE + "/join-requests")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(joinService);
  }

  @Test
  void createJoinRequestRefusalsKeepTheirCodeAndText() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(joinService.create(USER_ID, SHOP_ID)).thenThrow(
        new BaseException(ErrorCode.HR_WORKER_HAS_SHOP, ErrorCode.HR_WORKER_HAS_SHOP.getMessage()));

    mvc.perform(json(post(BASE + "/join-requests"), "{\"shopId\":\"" + SHOP_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("You are already part of a shop. Leave it first."))
        .andExpect(jsonPath("$.data.status").value(409));
  }

  @Test
  void withdrawPassesTheIdAndAnswersTheRequest() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    JoinRequestSelfResponse withdrawn = pendingJoin();
    withdrawn.setStatus(WorkerJoinStatus.WITHDRAWN);
    when(joinService.withdraw(USER_ID, REQUEST_ID)).thenReturn(withdrawn);

    mvc.perform(asWorker(post(BASE + "/join-requests/" + REQUEST_ID + "/withdraw")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
  }

  @Test
  void withdrawRefusesAMalformedIdBeforeTheServiceAndPassesAlreadyHandledThrough()
      throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    mvc.perform(asWorker(post(BASE + "/join-requests/req-1/withdraw")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("id must be a 24-character id"));
    verifyNoInteractions(joinService);

    when(joinService.withdraw(USER_ID, REQUEST_ID)).thenThrow(new BaseException(
        ErrorCode.HR_REQUEST_ALREADY_HANDLED, ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage()));
    mvc.perform(asWorker(post(BASE + "/join-requests/" + REQUEST_ID + "/withdraw")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("This request was already handled"));
  }

  // ---- pairing handshake with a login ----------------------------------------------------------

  @Test
  void claimPassesTheSameBodyAsTheDevicePathWithTheCaller() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(pairingService.claim(eq(USER_ID), any(ClaimPairingRequest.class))).thenReturn(
        new ClaimPairingResponse("claim-1", "Sri Ganesh Stores", "Priya Sharma", null));

    mvc.perform(json(post(BASE + "/pairings/claim"), claimBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.claimToken").value("claim-1"))
        .andExpect(jsonPath("$.data.employeeName").value("Priya Sharma"))
        .andExpect(jsonPath("$.data.shopName").value("Sri Ganesh Stores"));

    ArgumentCaptor<ClaimPairingRequest> body = ArgumentCaptor.forClass(ClaimPairingRequest.class);
    verify(pairingService).claim(eq(USER_ID), body.capture());
    assertEquals("ABCD-1234", body.getValue().getCode());
    assertEquals(INSTALLATION_ID, body.getValue().getInstallationId());
    assertEquals("Pixel 7", body.getValue().getPhoneModel());
  }

  @Test
  void claimAppliesTheDevicePathShapeChecksBeforeTheService() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    mvc.perform(json(post(BASE + "/pairings/claim"), "{\"installationId\":\"" + INSTALLATION_ID
            + "\",\"os\":\"ANDROID\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("code is required"));
    mvc.perform(json(post(BASE + "/pairings/claim"),
            "{\"code\":\"ABCD-1234\",\"installationId\":\"not-a-uuid\",\"os\":\"ANDROID\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("installationId must be a UUID"));
    mvc.perform(asWorker(post(BASE + "/pairings/claim")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("A request body is required"));
    verifyNoInteractions(pairingService);
  }

  @Test
  void claimForSomeoneElsesCodeIs403WithTheTextAndCountsTowardsTheLockout() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(pairingService.claim(eq(USER_ID), any(ClaimPairingRequest.class))).thenThrow(
        new BaseException(ErrorCode.HR_PAIRING_CODE_WRONG,
            ErrorCode.HR_PAIRING_CODE_WRONG.getMessage()));

    // five refused codes from one installation lock it (HR R2.5)
    for (int i = 0; i < AppRateLimiter.CODE_FAILURE_LIMIT; i++) {
      mvc.perform(json(post(BASE + "/pairings/claim"), claimBody()))
          .andExpect(status().is(ErrorCode.HR_PAIRING_CODE_WRONG.getHttpStatus().value()));
    }
    mvc.perform(json(post(BASE + "/pairings/claim"), claimBody()))
        .andExpect(status().is(ErrorCode.HR_PAIRING_CODE_LOCKED.getHttpStatus().value()));
    verify(pairingService, org.mockito.Mockito.times(AppRateLimiter.CODE_FAILURE_LIMIT))
        .claim(eq(USER_ID), any(ClaimPairingRequest.class));
  }

  @Test
  void aWrongEmployeeRefusalPassesThroughWithItsText() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(pairingService.claim(eq(USER_ID), any(ClaimPairingRequest.class))).thenThrow(
        new BaseException(ErrorCode.HR_PAIRING_WRONG_EMPLOYEE,
            ErrorCode.HR_PAIRING_WRONG_EMPLOYEE.getMessage()));

    mvc.perform(json(post(BASE + "/pairings/claim"), claimBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error")
            .value("This code is for a different employee. Ask your manager."));
  }

  @Test
  void completePassesTheBodyAndAnswersTheBootstrap() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    PairingBootstrapResponse bootstrap = new PairingBootstrapResponse();
    bootstrap.setPairingId("66f1a2b3c4d5e6f7a8b9c0aa");
    bootstrap.setShopId(SHOP_ID);
    bootstrap.setEmployeeId(EMPLOYEE_ID);
    when(pairingService.complete(eq(USER_ID), any(CompletePairingRequest.class)))
        .thenReturn(bootstrap);

    mvc.perform(json(post(BASE + "/pairings/complete"), completeBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pairingId").value("66f1a2b3c4d5e6f7a8b9c0aa"))
        .andExpect(jsonPath("$.data.employeeId").value(EMPLOYEE_ID));

    ArgumentCaptor<CompletePairingRequest> body =
        ArgumentCaptor.forClass(CompletePairingRequest.class);
    verify(pairingService).complete(eq(USER_ID), body.capture());
    assertEquals("claim-1", body.getValue().getClaimToken());
    assertEquals("punch-key", body.getValue().getPunchPublicKey());
    assertEquals(1, body.getValue().getConsentVersion());
  }

  @Test
  void completeWithoutAKeyIs400BeforeTheService() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    mvc.perform(json(post(BASE + "/pairings/complete"), "{\"claimToken\":\"c\","
            + "\"installationId\":\"" + INSTALLATION_ID + "\",\"devicePublicKey\":\"d\","
            + "\"consentVersion\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("punchPublicKey is required"));
    verifyNoInteractions(pairingService);
  }

  @Test
  void abandonPassesTheTokenAndIsIdempotent() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());

    mvc.perform(json(post(BASE + "/pairings/abandon"), "{\"claimToken\":\"claim-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.abandoned").value(true));
    verify(pairingService).abandon(USER_ID, "claim-1");

    mvc.perform(json(post(BASE + "/pairings/abandon"), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("claimToken is required"));
  }

  // ---- history ---------------------------------------------------------------------------------

  @Test
  void punchesComeFromTheTokenAndIgnoreAnyEmployeeIdInTheQuery() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.punches(USER_ID, "2026-05-01", "2026-05-12")).thenReturn(List.of(
        new PunchResponse("p1", EMPLOYEE_ID, NOW, "14:42", "2026-05-12", PunchSource.BIOMETRIC_APP,
            PunchDirection.IN, null, null, List.of(), true, "Pixel 7")));

    mvc.perform(asWorker(get(BASE + "/punches").param("from", "2026-05-01")
            .param("to", "2026-05-12").param("employeeId", "someone-else")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value("p1"))
        .andExpect(jsonPath("$.data[0].employeeId").value(EMPLOYEE_ID))
        .andExpect(jsonPath("$.data[0].counted").value(true));
    verify(selfService).punches(USER_ID, "2026-05-01", "2026-05-12");
  }

  @Test
  void punchesWithoutARangeDefaultsToTheLast31Days() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.punches(USER_ID, null, null)).thenReturn(List.of());

    mvc.perform(asWorker(get(BASE + "/punches")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
    verify(selfService).punches(eq(USER_ID), isNull(), isNull());
  }

  @Test
  void punchesRefuseAMalformedDateBeforeTheServiceAndATooWideRangeFromIt() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    mvc.perform(asWorker(get(BASE + "/punches").param("from", "01/05/2026")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("from must be a date like 2025-04-07"));
    verify(selfService, never()).punches(anyString(), any(), any());

    when(selfService.punches(USER_ID, "2026-01-01", "2026-05-12"))
        .thenThrow(new ValidationException("The range may cover at most 31 days"));
    mvc.perform(asWorker(get(BASE + "/punches").param("from", "2026-01-01")
            .param("to", "2026-05-12")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("The range may cover at most 31 days"));
  }

  @Test
  void punchesForAWorkerWithoutAnEmployeeIs409NotActive() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.punches(USER_ID, null, null)).thenThrow(new BaseException(
        ErrorCode.HR_WORKER_NOT_ACTIVE, ErrorCode.HR_WORKER_NOT_ACTIVE.getMessage()));

    mvc.perform(asWorker(get(BASE + "/punches")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("You are not part of a shop."));
  }

  // ---- profile ---------------------------------------------------------------------------------

  @Test
  void profileAnswersTheOwnRecordWithoutPayBankOrUpiFields() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.profile(USER_ID)).thenReturn(new WorkerProfileResponse("Priya Sharma",
        "9876543210", "priya@example.com", "/api/v1/users/" + USER_ID + "/photo",
        "Sri Ganesh Stores", "EMP-015", "2026-05-12", true, PairingStateResponse.State.PAIRED));

    mvc.perform(asWorker(get(BASE + "/profile")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Priya Sharma"))
        .andExpect(jsonPath("$.data.phone").value("9876543210"))
        .andExpect(jsonPath("$.data.email").value("priya@example.com"))
        .andExpect(jsonPath("$.data.photoUrl").value("/api/v1/users/" + USER_ID + "/photo"))
        .andExpect(jsonPath("$.data.shopName").value("Sri Ganesh Stores"))
        .andExpect(jsonPath("$.data.employeeCode").value("EMP-015"))
        .andExpect(jsonPath("$.data.joinDate").value("2026-05-12"))
        .andExpect(jsonPath("$.data.profileRequested").value(true))
        .andExpect(jsonPath("$.data.pairingState").value("PAIRED"))
        .andExpect(jsonPath("$.data.salaryRevisions").doesNotExist())
        .andExpect(jsonPath("$.data.bankAccountNumber").doesNotExist())
        .andExpect(jsonPath("$.data.ifsc").doesNotExist())
        .andExpect(jsonPath("$.data.upiId").doesNotExist())
        .andExpect(jsonPath("$.data.paymentMethod").doesNotExist());
  }

  @Test
  void updateProfilePassesNameAndPhoneAndAnswersTheProfile() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.updateProfile(USER_ID, "Priya S", "+91 98765 43210")).thenReturn(
        new WorkerProfileResponse("Priya S", "9876543210", "priya@example.com", null, null, null,
            null, false, PairingStateResponse.State.NOT_PAIRED));

    mvc.perform(json(put(BASE + "/profile"),
            "{\"name\":\"Priya S\",\"phone\":\"+91 98765 43210\",\"email\":\"new@x.in\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Priya S"))
        .andExpect(jsonPath("$.data.phone").value("9876543210"))
        .andExpect(jsonPath("$.data.email").value("priya@example.com"))
        .andExpect(jsonPath("$.data.pairingState").value("NOT_PAIRED"));
    verify(selfService).updateProfile(USER_ID, "Priya S", "+91 98765 43210");
  }

  @Test
  void updateProfileWithoutABodyIs400AndAnInvalidPhoneIsTheServices400() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    mvc.perform(asWorker(put(BASE + "/profile")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("A request body is required"));
    verify(selfService, never()).updateProfile(anyString(), any(), any());

    when(selfService.updateProfile(USER_ID, "Priya", "12345"))
        .thenThrow(new ValidationException("Enter a 10-digit mobile number."));
    mvc.perform(json(put(BASE + "/profile"), "{\"name\":\"Priya\",\"phone\":\"12345\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Enter a 10-digit mobile number."));
  }

  // ---- Leave this shop -------------------------------------------------------------------------

  @Test
  void leaveAnswersTheNewState() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    WorkerStateResponse state = new WorkerStateResponse();
    state.setState(State.NO_SHOP);
    state.setModules(List.of());
    state.setNotifications(new WorkerStateResponse.Notifications(0, List.of()));
    when(selfService.leave(USER_ID)).thenReturn(state);

    mvc.perform(asWorker(post(BASE + "/leave")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("NO_SHOP"))
        .andExpect(jsonPath("$.data.modules.length()").value(0));
    verify(selfService).leave(USER_ID);
  }

  @Test
  void leaveWithoutAnEmployeeIs409() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.leave(USER_ID)).thenThrow(new BaseException(
        ErrorCode.HR_WORKER_NOT_ACTIVE, ErrorCode.HR_WORKER_NOT_ACTIVE.getMessage()));

    mvc.perform(asWorker(post(BASE + "/leave")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("You are not part of a shop."));
  }

  // ---- notifications ---------------------------------------------------------------------------

  @Test
  void notificationsAnswerTheAccountsRowsAndTheUnreadCount() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    when(selfService.notifications(USER_ID)).thenReturn(new HrAccountNotificationsResponse(
        List.of(new HrNotificationResponse(NOTIFICATION_ID, HrNotificationType.JOIN_REQUEST_APPROVED,
            null, REQUEST_ID, "Sri Ganesh Stores accepted you", NOW, false)), 1));

    mvc.perform(asWorker(get(BASE + "/notifications")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unread").value(1))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(NOTIFICATION_ID))
        .andExpect(jsonPath("$.data.items[0].type").value("JOIN_REQUEST_APPROVED"))
        .andExpect(jsonPath("$.data.items[0].joinRequestId").value(REQUEST_ID))
        .andExpect(jsonPath("$.data.items[0].text").value("Sri Ganesh Stores accepted you"))
        .andExpect(jsonPath("$.data.items[0].read").value(false));
  }

  @Test
  void markReadPassesTheIdAndAnswersRead() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());

    mvc.perform(asWorker(post(BASE + "/notifications/" + NOTIFICATION_ID + "/read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.read").value(true));
    verify(selfService).markRead(USER_ID, NOTIFICATION_ID);
  }

  @Test
  void markReadRefusesAMalformedIdBeforeTheServiceAndPassesA404Through() throws Exception {
    when(selfService.requireWorkerAccess(USER_ID)).thenReturn(workerAccount());
    mvc.perform(asWorker(post(BASE + "/notifications/n1/read")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("id must be a 24-character id"));
    verify(selfService, never()).markRead(anyString(), anyString());

    org.mockito.Mockito.doThrow(new ResourceNotFoundException("Notification", "id",
        NOTIFICATION_ID)).when(selfService).markRead(USER_ID, NOTIFICATION_ID);
    mvc.perform(asWorker(post(BASE + "/notifications/" + NOTIFICATION_ID + "/read")))
        .andExpect(status().isNotFound());
  }

  // ---- no caller anywhere ----------------------------------------------------------------------

  @Test
  void everyRouteWithoutACallerIs401BeforeAnyService() throws Exception {
    List<MockHttpServletRequestBuilder> calls = List.of(
        get(BASE + "/shops").param("ownerEmail", "ramesh@example.com"),
        post(BASE + "/join-requests").contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopId\":\"" + SHOP_ID + "\"}"),
        post(BASE + "/pairings/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody()),
        get(BASE + "/punches"),
        get(BASE + "/profile"),
        post(BASE + "/leave"),
        get(BASE + "/notifications"));
    for (MockHttpServletRequestBuilder call : calls) {
      mvc.perform(call.requestAttr("shopId", SHOP_ID))
          .andExpect(status().isUnauthorized());
    }
    verifyNoInteractions(stateService, joinService, pairingService, selfService);
  }

  /** The legacy proof exchange is gone (worker-app D11): no such route. */
  @Test
  void thereIsNoPairingLinkRoute() throws Exception {
    mvc.perform(json(post(BASE + "/pairing-link"), "{\"proof\":\"p\"}"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(pairingService, joinService, selfService);
  }

  /** The employee is never a path or body parameter of any worker route (R8.4, R11.2). */
  @Test
  void noRouteTakesAnEmployeeId() {
    for (java.lang.reflect.Method m : WorkerController.class.getDeclaredMethods()) {
      for (java.lang.reflect.Parameter p : m.getParameters()) {
        org.springframework.web.bind.annotation.PathVariable path =
            p.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
        org.springframework.web.bind.annotation.RequestParam query =
            p.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
        String name = path != null ? (path.value().isEmpty() ? p.getName() : path.value())
            : query != null ? (query.value().isEmpty() ? p.getName() : query.value()) : null;
        if (name != null) {
          assertEquals(false, name.toLowerCase().contains("employee"),
              m.getName() + " takes " + name);
        }
      }
    }
  }
}
