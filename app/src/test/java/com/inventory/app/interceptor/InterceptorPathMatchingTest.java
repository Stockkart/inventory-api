package com.inventory.app.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.plan.api.PlanStatus;
import com.inventory.user.service.TokenValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Public paths (authentication) and the plan-expiry allow-list match the decoded path Spring
 * routes on, and a dot-segment path never counts as public or allowed.
 */
@ExtendWith(MockitoExtension.class)
class InterceptorPathMatchingTest {

  @Mock private TokenValidationService tokenValidationService;
  @InjectMocks private AuthenticationInterceptor authentication;

  @ParameterizedTest
  @ValueSource(strings = {"/api/product/../v1/accounting/journals", "/m/../api/v1/hr/employees",
      "/api/product/%2e%2e/v1/hr/employees", "/api/v1/auth/login/../../hr/employees"})
  void dotSegmentPathsAreNeverPublic(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);

    assertThrows(AuthenticationException.class,
        () -> authentication.preHandle(request, new MockHttpServletResponse(), null));
    verifyNoInteractions(tokenValidationService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/auth/login;jsessionid=1",
      "/api/v1/plans", "/api/v1/plans;x=1"})
  void publicPathsStillWork(String path) throws Exception {
    assertTrue(authentication.preHandle(new MockHttpServletRequest("POST", path),
        new MockHttpServletResponse(), null));
  }

  /**
   * Attendance app device paths carry no Bearer token; the signed envelope interceptor in the HR
   * module authenticates them (AGENTS X3: listed here and excluded in CorsConfig).
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/attendance-app/pairings/me/unpair",
      "/api/v1/hr/attendance-app/me",
      "/api/v1/hr/attendance-app/punches", "/api/v1/hr/attendance-app/consent",
      "/api/v1/hr/attendance-app/consent/latest"})
  void attendanceAppDevicePathsNeedNoBearerToken(String path) throws Exception {
    assertTrue(authentication.preHandle(new MockHttpServletRequest("POST", path),
        new MockHttpServletResponse(), null));
    verifyNoInteractions(tokenValidationService);
  }

  /**
   * Health, unknown paths and the removed unsigned handshake (worker-app D11: {@code
   * /pairings/claim|complete|abandon}) are ordinary Bearer-chain routes.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/attendance-app/health", "/api/v1/hr/attendance-app",
      "/api/v1/hr/attendance-app/punches/../health", "/api/v1/hr/attendance-app/mex",
      "/api/v1/hr/attendance-app/pairings/../../employees",
      "/api/v1/hr/attendance-app/pairings/claim", "/api/v1/hr/attendance-app/pairings/complete",
      "/api/v1/hr/attendance-app/pairings/abandon", "/api/v1/hr/attendance-app/pairings"})
  void attendanceAppHealthUnknownAndRemovedPathsStayOnTheBearerChain(String path) {
    assertThrows(AuthenticationException.class,
        () -> authentication.preHandle(new MockHttpServletRequest("GET", path),
            new MockHttpServletResponse(), null));
  }

  @Test
  void theAllowListOfPlanExpiryIgnoresDotSegments() {
    PlanExpiryInterceptor planExpiry = new PlanExpiryInterceptor();
    PlanStatus plans = org.mockito.Mockito.mock(PlanStatus.class);
    when(plans.isPlanExpired("s1")).thenReturn(true);
    org.springframework.test.util.ReflectionTestUtils.setField(planExpiry, "planStatus", plans);

    MockHttpServletRequest sneaky =
        new MockHttpServletRequest("GET", "/api/v1/plans/../accounting/journals");
    sneaky.setAttribute("shopId", "s1");
    BaseException e = assertThrows(BaseException.class,
        () -> planExpiry.preHandle(sneaky, new MockHttpServletResponse(), null));
    assertEquals(ErrorCode.PLAN_EXPIRED, e.getErrorCode());

    MockHttpServletRequest renew = new MockHttpServletRequest("GET", "/api/v1/plans;x=1");
    renew.setAttribute("shopId", "s1");
    assertTrue(planExpiry.preHandle(renew, new MockHttpServletResponse(), null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/me/payslips", "/api/v1/hr/me/payslips/abc/pdf",
      "/api/v1/hr/me/payslips;x=1"})
  void ownPayslipsStayAvailableAfterThePlanExpired(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setAttribute("shopId", "s1");

    assertTrue(expiredPlanInterceptor().preHandle(request, new MockHttpServletResponse(), null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/me", "/api/v1/hr/me/payslipsX", "/api/v1/hr/me/attendance",
      "/api/v1/hr/me/payslips/../attendance", "/api/v1/hr/payroll-runs/r1/lines/l1/payslip",
      "/api/v1/hr/worker", "/api/v1/hr/workerX/me", "/api/v1/hr/worker/../employees",
      "/api/v1/hr/join-requests", "/api/v1/hr/join-requests/abc/approve"})
  void otherHrPathsAreStillBlockedAfterThePlanExpired(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setAttribute("shopId", "s1");

    BaseException e = assertThrows(BaseException.class, () -> expiredPlanInterceptor()
        .preHandle(request, new MockHttpServletResponse(), null));
    assertEquals(ErrorCode.PLAN_EXPIRED, e.getErrorCode());
  }

  /**
   * Stockkart Team app (worker-app design "Request paths"): a worker's Home, state, history and
   * profile keep working while the shop's plan is expired; the manager inbox above does not.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/worker/me", "/api/v1/hr/worker/punches",
      "/api/v1/hr/worker/profile", "/api/v1/hr/worker/notifications/n1/read",
      "/api/v1/hr/worker/me;x=1"})
  void workerPathsStayAvailableAfterThePlanExpired(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setAttribute("shopId", "s1");

    assertTrue(expiredPlanInterceptor().preHandle(request, new MockHttpServletResponse(), null));
  }

  /** The device paths leave the Bearer chain; the worker and inbox paths do not. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/worker/me", "/api/v1/hr/worker/pairings/claim",
      "/api/v1/hr/join-requests", "/api/v1/hr/attendance-app/health"})
  void workerAndInboxPathsStayOnTheBearerChain(String path) {
    assertThrows(AuthenticationException.class,
        () -> authentication.preHandle(new MockHttpServletRequest("GET", path),
            new MockHttpServletResponse(), null));
  }

  /**
   * Non-HR module paths with an encoded or literal backslash. Embedded Tomcat 10.1.24 (this
   * build) answers {@code %5C}, {@code %5c}, {@code \} and {@code %2F} with 400 before any
   * interceptor runs (probed with a throwaway loopback Tomcat in run B5), so the interceptor's
   * 403 for them can only be reached behind a container that lets them through, where it is
   * the safe answer; clean paths of the same module are untouched.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/inventory/a%5Cb", "/api/v1/inventory/a%5cb",
      "/api/v1/inventory/a\\b", "/api/v1/accounting%5C..%5Chr/employees"})
  void aBackslashInAnyModulePathIsRefusedBeforeItsRules(String path) {
    com.inventory.user.service.RbacService rbac =
        org.mockito.Mockito.mock(com.inventory.user.service.RbacService.class);
    RbacModuleInterceptor interceptor = new RbacModuleInterceptor();
    org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "rbacService", rbac);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setAttribute("userId", "u1");
    request.setAttribute("shopId", "s1");

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verifyNoInteractions(rbac);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/inventory/search", "/api/v1/inventory/lot%20one",
      "/api/v1/customers/c1;v=2"})
  void cleanNonHrModulePathsPassUnchanged(String path) {
    com.inventory.user.service.RbacService rbac =
        org.mockito.Mockito.mock(com.inventory.user.service.RbacService.class);
    RbacModuleInterceptor interceptor = new RbacModuleInterceptor();
    org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "rbacService", rbac);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setAttribute("userId", "u1");
    request.setAttribute("shopId", "s1");

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
    verifyNoInteractions(rbac);
  }

  /**
   * Worker-app R9.1 (design DD3, Property 21): the worker credential interceptor, registered by
   * {@code CorsConfig} on exactly {@code /api/v1/hr/worker/**}, refuses a request carrying either
   * device-envelope header with {@code HR_TWO_CREDENTIALS} and touches nothing else; the timestamp
   * and nonce alone are not a credential.
   */
  @ParameterizedTest
  @ValueSource(strings = {"X-Pairing-Id", "X-Request-Signature"})
  void aWorkerRequestWithAnEnvelopeHeaderIsRefusedAsTwoCredentials(String header) {
    com.inventory.hr.service.WorkerCredentialInterceptor interceptor =
        new com.inventory.hr.service.WorkerCredentialInterceptor();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hr/worker/me");
    request.addHeader("Authorization", "Bearer token");
    request.addHeader(header, "something");

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
    assertEquals(ErrorCode.HR_TWO_CREDENTIALS, e.getErrorCode());
    assertEquals("A request may carry a login or a phone signature, not both.", e.getMessage());
  }

  @Test
  void aWorkerRequestWithoutEnvelopeHeadersPassesTheCredentialCheck() {
    com.inventory.hr.service.WorkerCredentialInterceptor interceptor =
        new com.inventory.hr.service.WorkerCredentialInterceptor();
    MockHttpServletRequest plain = new MockHttpServletRequest("POST",
        "/api/v1/hr/worker/pairings/claim");
    plain.addHeader("Authorization", "Bearer token");
    plain.addHeader("X-Request-Timestamp", "1700000000");
    plain.addHeader("X-Request-Nonce", "0f8fad5b-d9cb-469f-a165-70867728950e");
    assertTrue(interceptor.preHandle(plain, new MockHttpServletResponse(), null));

    MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS",
        "/api/v1/hr/worker/me");
    preflight.addHeader("X-Pairing-Id", "66f1a2b3c4d5e6f7a8b9c0d1");
    assertTrue(interceptor.preHandle(preflight, new MockHttpServletResponse(), null));
  }

  /** The pattern {@code CorsConfig} registers it on covers the worker prefix and nothing beside it. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/worker/me", "/api/v1/hr/worker/pairings/claim",
      "/api/v1/hr/worker/notifications/n1/read"})
  void theWorkerCredentialPatternMatchesTheWorkerPrefix(String path) {
    org.springframework.web.util.pattern.PathPattern pattern =
        org.springframework.web.util.pattern.PathPatternParser.defaultInstance.parse(
            com.inventory.hr.service.WorkerCredentialInterceptor.PATH_PATTERN);
    assertTrue(pattern.matches(org.springframework.http.server.PathContainer.parsePath(path)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/workerX/me", "/api/v1/hr/me",
      "/api/v1/hr/attendance-app/me", "/api/v1/hr/join-requests", "/api/v1/hr/worker-x"})
  void theWorkerCredentialPatternLeavesOtherPathsAlone(String path) {
    org.springframework.web.util.pattern.PathPattern pattern =
        org.springframework.web.util.pattern.PathPatternParser.defaultInstance.parse(
            com.inventory.hr.service.WorkerCredentialInterceptor.PATH_PATTERN);
    assertEquals(false,
        pattern.matches(org.springframework.http.server.PathContainer.parsePath(path)));
  }

  private static PlanExpiryInterceptor expiredPlanInterceptor() {
    PlanExpiryInterceptor planExpiry = new PlanExpiryInterceptor();
    PlanStatus plans = org.mockito.Mockito.mock(PlanStatus.class);
    org.mockito.Mockito.lenient().when(plans.isPlanExpired("s1")).thenReturn(true);
    org.springframework.test.util.ReflectionTestUtils.setField(planExpiry, "planStatus", plans);
    return planExpiry;
  }
}
