package com.inventory.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.inventory.app.interceptor.AuthenticationInterceptor;
import com.inventory.app.interceptor.PlanExpiryInterceptor;
import com.inventory.app.interceptor.RbacModuleInterceptor;
import com.inventory.hr.service.AttendanceAppAuthInterceptor;
import com.inventory.hr.service.WorkerCredentialInterceptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The interceptor chain as {@link CorsConfig#addInterceptors} wires it (AGENTS L7, worker-app
 * design "Request paths", R9.1, R14.6): which interceptors a path meets, and in which order.
 * The rules each interceptor applies once reached are tested with the interceptor itself
 * ({@code RbacModuleInterceptorTest}, {@code InterceptorPathMatchingTest},
 * {@code AttendanceAppAuthInterceptorTest}); this test pins the mapping only.
 */
class CorsConfigTest {

  private static final String DEVICE = AttendanceAppAuthInterceptor.PATH_PREFIX;

  private AuthenticationInterceptor authentication;
  private RbacModuleInterceptor rbac;
  private PlanExpiryInterceptor planExpiry;
  private AttendanceAppAuthInterceptor attendanceApp;
  private WorkerCredentialInterceptor workerCredential;
  private List<MappedInterceptor> chain;

  @BeforeEach
  void wire() {
    authentication = mock(AuthenticationInterceptor.class);
    rbac = mock(RbacModuleInterceptor.class);
    planExpiry = mock(PlanExpiryInterceptor.class);
    attendanceApp = mock(AttendanceAppAuthInterceptor.class);
    workerCredential = mock(WorkerCredentialInterceptor.class);

    CorsConfig config = new CorsConfig();
    ReflectionTestUtils.setField(config, "authenticationInterceptor", authentication);
    ReflectionTestUtils.setField(config, "rbacModuleInterceptor", rbac);
    ReflectionTestUtils.setField(config, "planExpiryInterceptor", planExpiry);
    ReflectionTestUtils.setField(config, "attendanceAppAuthInterceptor", attendanceApp);
    ReflectionTestUtils.setField(config, "workerCredentialInterceptor", workerCredential);

    ExposingRegistry registry = new ExposingRegistry();
    config.addInterceptors(registry);
    chain = registry.mapped();
  }

  /** {@code getInterceptors()} is protected; every registration here is path-mapped. */
  private static final class ExposingRegistry extends InterceptorRegistry {
    List<MappedInterceptor> mapped() {
      List<MappedInterceptor> out = new ArrayList<>();
      for (Object o : getInterceptors()) {
        assertTrue(o instanceof MappedInterceptor, o + " is registered without a path");
        out.add((MappedInterceptor) o);
      }
      return out;
    }
  }

  /** The interceptors a GET to {@code path} meets, in registration order. */
  private List<HandlerInterceptor> interceptorsOn(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    ServletRequestPathUtils.parseAndCache(request);
    List<HandlerInterceptor> met = new ArrayList<>();
    for (MappedInterceptor m : chain) {
      if (m.matches(request)) {
        met.add(m.getInterceptor());
      }
    }
    return met;
  }

  /** Device paths meet the envelope interceptor and none of the Bearer chain (HR D7, R14.6). */
  @ParameterizedTest
  @ValueSource(strings = {DEVICE + "/me", DEVICE + "/punches", DEVICE + "/consent",
      DEVICE + "/consent/latest", DEVICE + "/pairings/me/unpair"})
  void devicePathsMeetOnlyTheEnvelopeInterceptor(String path) {
    assertEquals(List.of(attendanceApp), interceptorsOn(path), path);
  }

  /**
   * Health, any look-alike under the prefix, and the removed unsigned handshake (worker-app D11,
   * DD4) are ordinary Bearer-chain routes: not device paths, never the envelope.
   */
  @ParameterizedTest
  @ValueSource(strings = {DEVICE + "/health", "/api/v1/hr/attendance-appX/me",
      "/api/v1/hr/attendance-app-2/punches", DEVICE + "/pairings/claim",
      DEVICE + "/pairings/complete", DEVICE + "/pairings/abandon", DEVICE + "/pairings"})
  void healthLookAlikeAndRemovedPathsStayOnTheBearerChain(String path) {
    assertEquals(List.of(authentication, rbac, planExpiry), interceptorsOn(path), path);
  }

  /**
   * Worker paths meet the credential check first, then the whole Bearer chain (R9.1): the
   * envelope-header refusal happens before the token is read, and RBAC / plan expiry still run
   * (their own skip and allow rules let the path through).
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/worker/me", "/api/v1/hr/worker/pairings/claim",
      "/api/v1/hr/worker/join-requests/abc/withdraw", "/api/v1/hr/worker/notifications/n1/read"})
  void workerPathsMeetTheCredentialCheckBeforeTheBearerChain(String path) {
    assertEquals(List.of(workerCredential, authentication, rbac, planExpiry),
        interceptorsOn(path), path);
  }

  /** The manager inbox and other staff paths are plain Bearer-chain paths. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/join-requests", "/api/v1/hr/join-requests/abc/approve",
      "/api/v1/hr/join-requests/bulk/reject", "/api/v1/hr/employees/e1/request-profile",
      "/api/v1/hr/me", "/api/v1/hr/workerX/me", "/api/v1/users/me/profile",
      "/api/v1/users/u1/photo", "/api/v1/accounting/journals"})
  void staffAndAccountPathsMeetTheBearerChainOnly(String path) {
    assertEquals(List.of(authentication, rbac, planExpiry), interceptorsOn(path), path);
  }

  /** {@code /admin/**} is authenticated but has no module or plan rule (CorsConfig, L7). */
  @Test
  void adminPathsAreAuthenticatedOnly() {
    assertEquals(List.of(authentication), interceptorsOn("/admin/anything"));
  }

  /** Public paths meet no interceptor at all (AGENTS X3). */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/auth/signup", "/m/upload/abc",
      "/api/product/get-plugin", "/actuator/health"})
  void publicPathsMeetNoInterceptor(String path) {
    assertEquals(List.of(), interceptorsOn(path), path);
  }

  @Test
  void theEnvelopeInterceptorIsMappedOnExactlyTheDevicePaths() {
    MappedInterceptor envelope = chain.stream()
        .filter(m -> m.getInterceptor() == attendanceApp).findFirst().orElseThrow();
    assertEquals(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS,
        List.of(envelope.getIncludePathPatterns()));
    assertNull(envelope.getExcludePathPatterns(), "nothing to exclude: health is not included");
    assertTrue(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS.contains(DEVICE + "/pairings/me/**"));
    assertTrue(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS.stream()
        .noneMatch(p -> p.equals(DEVICE + "/pairings/**")),
        "the pairing sub-tree is /pairings/me/** only: the unsigned handshake is gone (D11)");
  }

  @Test
  void theWorkerCredentialInterceptorIsMappedOnExactlyTheWorkerPrefix() {
    MappedInterceptor worker = chain.stream()
        .filter(m -> m.getInterceptor() == workerCredential).findFirst().orElseThrow();
    assertEquals(List.of(WorkerCredentialInterceptor.PATH_PATTERN),
        List.of(worker.getIncludePathPatterns()));
    assertNull(worker.getExcludePathPatterns(), "nothing under the worker prefix is excluded");
  }

  /** Every Bearer-chain interceptor excludes every device path (listed one by one, not /**). */
  @Test
  void everyBearerChainInterceptorExcludesEveryDevicePath() {
    for (HandlerInterceptor bearer : List.of(authentication, rbac, planExpiry)) {
      MappedInterceptor mapped = chain.stream()
          .filter(m -> m.getInterceptor() == bearer).findFirst().orElseThrow();
      List<String> excluded = List.of(mapped.getExcludePathPatterns());
      assertTrue(excluded.containsAll(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS),
          bearer + " excludes " + excluded);
      assertTrue(excluded.stream().noneMatch(p -> p.equals(DEVICE + "/**")),
          "the whole prefix must not be excluded, or health leaves the chain: " + excluded);
    }
  }
}
