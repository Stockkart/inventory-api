package com.inventory.hr.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.response.AppLocationSettingsResponse;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.rest.dto.response.AppStatusResponse;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.ConsentResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.service.AppPunchService;
import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.AppRequestContext;
import com.inventory.hr.service.ConsentVersions;
import com.inventory.hr.service.DevicePairingService;
import com.inventory.metrics.MetricsWrapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
 * The Attendance app endpoints through MVC: request shapes, the {@code ApiResponse} envelope, the
 * error envelope for a refusal, and the per-installation lockout (R2.5). The interceptor chain is
 * not part of a standalone MockMvc, so the unsigned calls carry no Bearer and no envelope, exactly
 * as the phone sends them, and the signed calls get the {@link AppRequestContext} the interceptor
 * would have stored set directly as a request attribute.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceAppControllerTest {

  private static final String ROOT = "/api/v1/hr/attendance-app";
  private static final String BASE = ROOT + "/pairings";
  private static final String INSTALL = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final String TOKEN = "tok_abc";
  private static final Instant NOW = Instant.parse("2025-04-03T09:12:00Z");
  private static final String PAIRING_ID = "66f1a2b3c4d5e6f7a8b9c0d1";
  private static final String SHOP_ID = "66f1a2b3c4d5e6f7a8b9c0d2";
  private static final String EMPLOYEE_ID = "66f1a2b3c4d5e6f7a8b9c0d3";

  @Mock private DevicePairingService pairingService;
  @Mock private AppPunchService punchService;
  @Mock private MetricsWrapper metrics;

  private AppRateLimiter rateLimiter;
  private MockMvc mvc;
  private DevicePairing pairing;

  @BeforeEach
  void setUp() {
    rateLimiter = new AppRateLimiter(metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    AttendanceAppController controller = new AttendanceAppController();
    ReflectionTestUtils.setField(controller, "pairingService", pairingService);
    ReflectionTestUtils.setField(controller, "rateLimiter", rateLimiter);
    ReflectionTestUtils.setField(controller, "punchService", punchService);
    ReflectionTestUtils.setField(controller, "consentVersions", new ConsentVersions());
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();

    pairing = new DevicePairing();
    pairing.setId(PAIRING_ID);
    pairing.setShopId(SHOP_ID);
    pairing.setEmployeeId(EMPLOYEE_ID);
    pairing.setStatus(PairingStatus.ACTIVE);
    pairing.setPhoneModel("Redmi Note 11");
    pairing.setConsentVersion(1);
  }

  /** What {@code AttendanceAppAuthInterceptor} leaves on a verified request. */
  private MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) {
    return request.requestAttr(AppRequestContext.ATTRIBUTE, AppRequestContext.of(pairing));
  }

  private static String claimBody(String code) {
    return """
        {"code":"%s","installationId":"%s","os":"ANDROID","osVersion":"14",
         "phoneModel":"Redmi Note 11","appVersion":"1.0.0"}
        """.formatted(code, INSTALL);
  }

  // ---- claim -----------------------------------------------------------------------------------

  @Test
  void claimReturnsNamesConsentAndToken() throws Exception {
    ConsentVersions.ConsentVersion consent = new ConsentVersions().latest();
    when(pairingService.claim("ab cd-efgh", INSTALL, DeviceOs.ANDROID, "14", "Redmi Note 11",
        "1.0.0")).thenReturn(new ClaimPairingResponse(TOKEN, "Sri Stores", "Ravi Kumar", consent));

    mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody("ab cd-efgh")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.claimToken").value(TOKEN))
        .andExpect(jsonPath("$.data.shopName").value("Sri Stores"))
        .andExpect(jsonPath("$.data.employeeName").value("Ravi Kumar"))
        .andExpect(jsonPath("$.data.consent.version").value(consent.version()))
        .andExpect(jsonPath("$.data.consent.kind").value("RECONSENT_REQUIRED"))
        .andExpect(jsonPath("$.data.consent.recorded").isArray())
        .andExpect(jsonPath("$.data.consent.neverRecorded").isArray())
        .andExpect(jsonPath("$.data.consent.visibility").isArray());
  }

  @Test
  void aRefusalKeepsItsCodeStatusAndMessage() throws Exception {
    when(pairingService.claim(anyString(), eq(INSTALL), any(), any(), any(), any()))
        .thenThrow(new BaseException(ErrorCode.HR_PAIRING_CODE_EXPIRED,
            ErrorCode.HR_PAIRING_CODE_EXPIRED.getMessage()));

    mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody("ABCDEFGH")))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(ErrorCode.HR_PAIRING_CODE_EXPIRED.getMessage()))
        .andExpect(jsonPath("$.data.message").value(ErrorCode.HR_PAIRING_CODE_EXPIRED.getMessage()))
        .andExpect(jsonPath("$.data.status").value(410));
  }

  @Test
  void fiveRefusedCodesLockTheInstallationAndTheSixthNeverReachesTheService()
      throws Exception {
    when(pairingService.claim(anyString(), eq(INSTALL), any(), any(), any(), any()))
        .thenThrow(new BaseException(ErrorCode.HR_PAIRING_CODE_WRONG,
            ErrorCode.HR_PAIRING_CODE_WRONG.getMessage()));

    for (int i = 0; i < 5; i++) {
      mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
              .content(claimBody("WRONG" + i + "XX")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value(ErrorCode.HR_PAIRING_CODE_WRONG.getMessage()));
    }
    mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody("ABCDEFGH")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value("Too many wrong codes. Wait 15 minutes and try again."))
        .andExpect(jsonPath("$.data.status").value(429));
    verify(pairingService, times(5)).claim(anyString(), eq(INSTALL), any(), any(), any(), any());

    // another installation is not affected
    String other = "0f8fad5b-d9cb-469f-a165-70867728950e";
    when(pairingService.claim(anyString(), eq(other), any(), any(), any(), any()))
        .thenReturn(new ClaimPairingResponse(TOKEN, "Sri Stores", "Ravi Kumar",
            new ConsentVersions().latest()));
    mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody("ABCDEFGH").replace(INSTALL, other)))
        .andExpect(status().isOk());
  }

  @Test
  void aCompleteRefusalCountsTowardsTheSameLock() throws Exception {
    when(pairingService.claim(anyString(), eq(INSTALL), any(), any(), any(), any()))
        .thenThrow(new BaseException(ErrorCode.HR_PAIRING_CODE_WRONG, "wrong"));
    when(pairingService.complete(anyString(), eq(INSTALL), anyString(), anyString(), anyInt(),
        isNull()))
        .thenThrow(new BaseException(ErrorCode.HR_PAIRING_CODE_EXPIRED, "expired"));
    for (int i = 0; i < 3; i++) {
      mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
          .content(claimBody("ABCDEFGH"))).andExpect(status().isNotFound());
    }
    for (int i = 0; i < 2; i++) {
      mvc.perform(post(BASE + "/complete").contentType(MediaType.APPLICATION_JSON)
          .content(completeBody())).andExpect(status().isGone());
    }
    mvc.perform(post(BASE + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content(completeBody()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value("Too many wrong codes. Wait 15 minutes and try again."));
    verify(pairingService, times(2)).complete(anyString(), eq(INSTALL), anyString(), anyString(),
        anyInt(), isNull());
  }

  @Test
  void claimRejectsAMalformedInstallationIdBeforeTheService() throws Exception {
    mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody("ABCDEFGH").replace(INSTALL, "not-a-uuid")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("installationId must be a UUID"));
    verifyNoInteractions(pairingService);
  }

  @Test
  void claimRejectsAnOverlongPhoneModel() throws Exception {
    String body = claimBody("ABCDEFGH").replace("Redmi Note 11", "x".repeat(81));
    mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("phoneModel must be at most 80 characters"));
    verifyNoInteractions(pairingService);
  }

  @Test
  void aMissingCodeIsA400NotACountedRefusal() throws Exception {
    for (int i = 0; i < 6; i++) {
      mvc.perform(post(BASE + "/claim").contentType(MediaType.APPLICATION_JSON)
              .content(claimBody("")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("code is required"));
    }
    verifyNoInteractions(pairingService);
  }

  // ---- complete --------------------------------------------------------------------------------

  private static String completeBody() {
    return """
        {"claimToken":"%s","installationId":"%s","punchPublicKey":"PUNCH-SPKI",
         "devicePublicKey":"DEVICE-SPKI","consentVersion":1}
        """.formatted(TOKEN, INSTALL);
  }

  @Test
  void completeReturnsTheBootstrapTheAppStores() throws Exception {
    when(pairingService.complete(TOKEN, INSTALL, "PUNCH-SPKI", "DEVICE-SPKI", 1, null))
        .thenReturn(new PairingBootstrapResponse("pairing-1", "shop-1", "emp-1", "Ravi Kumar",
            "Sri Stores", new AppLocationSettingsResponse(true, 12.97, 77.59, 150,
                OutsideAction.FLAG), 1));

    mvc.perform(post(BASE + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content(completeBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.pairingId").value("pairing-1"))
        .andExpect(jsonPath("$.data.shopId").value("shop-1"))
        .andExpect(jsonPath("$.data.employeeId").value("emp-1"))
        .andExpect(jsonPath("$.data.employeeName").value("Ravi Kumar"))
        .andExpect(jsonPath("$.data.shopName").value("Sri Stores"))
        .andExpect(jsonPath("$.data.location.locationCheck").value(true))
        .andExpect(jsonPath("$.data.location.shopLat").value(12.97))
        .andExpect(jsonPath("$.data.location.radiusMetres").value(150))
        .andExpect(jsonPath("$.data.location.outsideAction").value("FLAG"))
        .andExpect(jsonPath("$.data.consentVersion").value(1));
  }

  @Test
  void completeNeedsBothKeysAndTheConsentVersion() throws Exception {
    mvc.perform(post(BASE + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content(completeBody().replace("\"devicePublicKey\":\"DEVICE-SPKI\",", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("devicePublicKey is required"));
    mvc.perform(post(BASE + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content(completeBody().replace(",\"consentVersion\":1", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("consentVersion is required"));
    mvc.perform(post(BASE + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content(completeBody().replace(TOKEN, "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("claimToken is required"));
    verifyNoInteractions(pairingService);
  }

  // ---- abandon ---------------------------------------------------------------------------------

  @Test
  void abandonAnswersTrueAndInvalidatesTheCode() throws Exception {
    mvc.perform(post(BASE + "/abandon").contentType(MediaType.APPLICATION_JSON)
            .content("{\"claimToken\":\"" + TOKEN + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.abandoned").value(true));
    verify(pairingService).abandon(TOKEN);
  }

  @Test
  void abandonWithoutATokenIsA400() throws Exception {
    mvc.perform(post(BASE + "/abandon").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("claimToken is required"));
    verify(pairingService, never()).abandon(any());
  }

  @Test
  void abandonIsNotRateLimited() throws Exception {
    // the lockout counts code attempts; "No, this isn't me" is not one
    for (int i = 0; i < 7; i++) {
      mvc.perform(post(BASE + "/abandon").contentType(MediaType.APPLICATION_JSON)
              .content("{\"claimToken\":\"" + TOKEN + i + "\"}"))
          .andExpect(status().isOk());
    }
    verify(pairingService, times(7)).abandon(anyString());
    verifyNoInteractions(metrics);
  }

  // ---- consent/latest (unsigned) ---------------------------------------------------------------

  @Test
  void latestConsentNeedsNoContextAndCarriesNoShopData() throws Exception {
    ConsentVersions.ConsentVersion latest = new ConsentVersions().latest();
    mvc.perform(get(ROOT + "/consent/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.version").value(latest.version()))
        .andExpect(jsonPath("$.data.kind").value(latest.kind().name()))
        .andExpect(jsonPath("$.data.recorded.length()").value(latest.recorded().size()))
        .andExpect(jsonPath("$.data.neverRecorded.length()").value(latest.neverRecorded().size()))
        .andExpect(jsonPath("$.data.visibility.length()").value(latest.visibility().size()))
        .andExpect(jsonPath("$.data.shopId").doesNotExist())
        .andExpect(jsonPath("$.data.shopName").doesNotExist())
        .andExpect(jsonPath("$.data.employeeId").doesNotExist())
        .andExpect(jsonPath("$.data.employeeName").doesNotExist())
        .andExpect(jsonPath("$.data.pairingId").doesNotExist());
    verifyNoInteractions(pairingService, punchService);
  }

  // ---- signed calls: identity from the request attribute only ----------------------------------

  @Test
  void everySignedCallWithoutTheContextIsNotPairedAndReachesNoService() throws Exception {
    MockHttpServletRequestBuilder[] calls = {
        get(ROOT + "/me"),
        post(ROOT + "/punches").contentType(MediaType.APPLICATION_JSON).content(punchBody()),
        get(ROOT + "/punches").param("from", "2025-03-04").param("to", "2025-04-03"),
        post(ROOT + "/consent").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"),
        post(BASE + "/me/unpair"),
    };
    for (MockHttpServletRequestBuilder call : calls) {
      mvc.perform(call)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.error").value(ErrorCode.HR_APP_NOT_PAIRED.getMessage()))
          .andExpect(jsonPath("$.data.status").value(403));
    }
    verifyNoInteractions(pairingService, punchService);
  }

  @Test
  void meReadsThePairingFromTheContext() throws Exception {
    AppStatusResponse status = new AppStatusResponse("Ravi Kumar", "Sri Stores", "2025-04-03",
        true, true, null, PunchDirection.OUT, "09:12", null,
        new AppLocationSettingsResponse(true, 12.97, 77.59, 150, OutsideAction.FLAG),
        new AppStatusResponse.ConsentState(2, true, 1));
    when(punchService.status(pairing)).thenReturn(status);

    mvc.perform(signed(get(ROOT + "/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.employeeName").value("Ravi Kumar"))
        .andExpect(jsonPath("$.data.today").value("2025-04-03"))
        .andExpect(jsonPath("$.data.biometricApp").value(true))
        .andExpect(jsonPath("$.data.canPunch").value(true))
        .andExpect(jsonPath("$.data.nextDirection").value("OUT"))
        .andExpect(jsonPath("$.data.todayIn").value("09:12"))
        .andExpect(jsonPath("$.data.location.radiusMetres").value(150))
        .andExpect(jsonPath("$.data.consent.latestVersion").value(2))
        .andExpect(jsonPath("$.data.consent.reconsentRequired").value(true))
        .andExpect(jsonPath("$.data.consent.agreedVersion").value(1));

    ArgumentCaptor<DevicePairing> used = ArgumentCaptor.forClass(DevicePairing.class);
    verify(punchService).status(used.capture());
    assertSame(pairing, used.getValue(), "the pairing the interceptor loaded, not a re-read");
  }

  private static String punchBody() {
    // the body names another pairing, shop and employee: the controller must not believe it
    return """
        {"pairingId":"aaaaaaaaaaaaaaaaaaaaaaaa","shopId":"bbbbbbbbbbbbbbbbbbbbbbbb",
         "employeeId":"cccccccccccccccccccccccc","punchId":"3f2504e0-4f89-41d3-9a0c-0305e82c3301",
         "phoneTime":"2025-04-03T09:12:00Z","offline":false,"locationChecked":true,
         "distanceMetres":12,"accuracyMetres":8,"mock":false,"signature":"c2lnbmF0dXJl"}
        """;
  }

  @Test
  void punchHandsTheContextPairingAndTheBodyToTheService() throws Exception {
    when(punchService.punch(eq(pairing), any(AppPunchRequest.class))).thenReturn(
        new AppPunchResponse(AppPunchResponse.Outcome.STORED, PunchDirection.IN, NOW, "14:42",
            List.of(PunchFlag.OUTSIDE_SHOP), false, "14:42", null, PunchDirection.OUT));

    mvc.perform(signed(post(ROOT + "/punches").contentType(MediaType.APPLICATION_JSON)
            .content(punchBody())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.direction").value("IN"))
        .andExpect(jsonPath("$.data.atLocal").value("14:42"))
        .andExpect(jsonPath("$.data.flags[0]").value("OUTSIDE_SHOP"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.nextDirection").value("OUT"));

    ArgumentCaptor<DevicePairing> used = ArgumentCaptor.forClass(DevicePairing.class);
    ArgumentCaptor<AppPunchRequest> body = ArgumentCaptor.forClass(AppPunchRequest.class);
    verify(punchService).punch(used.capture(), body.capture());
    assertSame(pairing, used.getValue());
    assertEquals(PAIRING_ID, used.getValue().getId(), "identity is the envelope's, not the body's");
    // the body is passed through untouched: the service compares it with the pairing and refuses
    assertEquals("aaaaaaaaaaaaaaaaaaaaaaaa", body.getValue().getPairingId());
    assertEquals("3f2504e0-4f89-41d3-9a0c-0305e82c3301", body.getValue().getPunchId());
    assertEquals(12, body.getValue().getDistanceMetres());
    assertEquals("c2lnbmF0dXJl", body.getValue().getSignature());
  }

  @Test
  void punchRefusalKeepsTheServicesCodeAndMessage() throws Exception {
    when(punchService.punch(eq(pairing), any(AppPunchRequest.class)))
        .thenThrow(new BaseException(ErrorCode.HR_APP_OFF, ErrorCode.HR_APP_OFF.getMessage()));

    mvc.perform(signed(post(ROOT + "/punches").contentType(MediaType.APPLICATION_JSON)
            .content(punchBody())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(ErrorCode.HR_APP_OFF.getMessage()))
        .andExpect(jsonPath("$.data.message").value(ErrorCode.HR_APP_OFF.getMessage()));
  }

  @Test
  void punchWithoutABodyIsA400() throws Exception {
    mvc.perform(signed(post(ROOT + "/punches").contentType(MediaType.APPLICATION_JSON)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("A request body is required"));
    verifyNoInteractions(punchService);
  }

  @Test
  void myPunchesPassesTheRangeAndReturnsTheRows() throws Exception {
    when(punchService.myPunches(pairing, "2025-03-04", "2025-04-03")).thenReturn(List.of(
        new PunchResponse("p2", EMPLOYEE_ID, NOW, "14:42", "2025-04-03", PunchSource.BIOMETRIC_APP,
            PunchDirection.IN, 12, 8, List.of(PunchFlag.RECORDED_OFFLINE), true, "Redmi Note 11"),
        new PunchResponse("p1", EMPLOYEE_ID, NOW.minusSeconds(86400), "14:42", "2025-04-02",
            PunchSource.BIOMETRIC_APP, PunchDirection.IN, null, null, List.of(), false,
            "Redmi Note 11")));

    mvc.perform(signed(get(ROOT + "/punches").param("from", "2025-03-04")
            .param("to", "2025-04-03")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].id").value("p2"))
        .andExpect(jsonPath("$.data[0].source").value("BIOMETRIC_APP"))
        .andExpect(jsonPath("$.data[0].flags[0]").value("RECORDED_OFFLINE"))
        .andExpect(jsonPath("$.data[0].counted").value(true))
        .andExpect(jsonPath("$.data[1].counted").value(false));
  }

  @Test
  void myPunchesWithoutARangeLetsTheServiceDefaultIt() throws Exception {
    when(punchService.myPunches(pairing, null, null)).thenReturn(List.of());
    mvc.perform(signed(get(ROOT + "/punches")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(0));
    verify(punchService).myPunches(pairing, null, null);
  }

  @Test
  void myPunchesRejectsAMalformedDateBeforeTheService() throws Exception {
    mvc.perform(signed(get(ROOT + "/punches").param("from", "04/03/2025")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("from must be a date like 2025-04-07"));
    mvc.perform(signed(get(ROOT + "/punches").param("to", "2025-4-3")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("to must be a date like 2025-04-07"));
    verifyNoInteractions(punchService);
  }

  @Test
  void myPunchesRangeRefusalFromTheServiceIsA400() throws Exception {
    when(punchService.myPunches(pairing, "2025-01-01", "2025-04-03"))
        .thenThrow(new ValidationException("The range may cover at most 31 days"));
    mvc.perform(signed(get(ROOT + "/punches").param("from", "2025-01-01")
            .param("to", "2025-04-03")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("The range may cover at most 31 days"));
  }

  // ---- consent (signed) ------------------------------------------------------------------------

  @Test
  void consentRecordsTheVersionForTheContextPairing() throws Exception {
    when(pairingService.recordConsent(PAIRING_ID, 2)).thenReturn(new ConsentResponse(2, NOW));

    mvc.perform(signed(post(ROOT + "/consent").contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":2}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.agreedVersion").value(2))
        // the standalone MockMvc has no JavaTimeModule setup; the app's Jackson config makes this ISO
        .andExpect(jsonPath("$.data.at").exists());
    verify(pairingService).recordConsent(PAIRING_ID, 2);
  }

  @Test
  void consentWithoutAVersionIsA400() throws Exception {
    mvc.perform(signed(post(ROOT + "/consent").contentType(MediaType.APPLICATION_JSON)
            .content("{}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("version is required"));
    verifyNoInteractions(pairingService);
  }

  @Test
  void consentToAnUnknownVersionIsA400() throws Exception {
    when(pairingService.recordConsent(PAIRING_ID, 99))
        .thenThrow(new ValidationException("consentVersion is not a published version"));
    mvc.perform(signed(post(ROOT + "/consent").contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":99}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("consentVersion is not a published version"));
  }

  // ---- unpair by the worker (signed) -----------------------------------------------------------

  @Test
  void unpairMeUnpairsTheContextPairingAsTheWorker() throws Exception {
    when(pairingService.unpair(PAIRING_ID, UnpairReason.WORKER, DevicePairingService.SELF))
        .thenReturn(Optional.of(pairing));

    mvc.perform(signed(post(BASE + "/me/unpair")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.unpaired").value(true));
    verify(pairingService).unpair(PAIRING_ID, UnpairReason.WORKER, "self");
    verifyNoInteractions(punchService);
  }

  @Test
  void unpairMeOfAnAlreadyUnpairedPairingStillAnswersTrue() throws Exception {
    when(pairingService.unpair(PAIRING_ID, UnpairReason.WORKER, DevicePairingService.SELF))
        .thenReturn(Optional.empty());
    mvc.perform(signed(post(BASE + "/me/unpair")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unpaired").value(true));
  }
}
