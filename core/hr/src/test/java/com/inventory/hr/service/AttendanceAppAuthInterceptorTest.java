package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.repository.AppNonceRepository;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.service.AttendanceAppBodyCachingFilter.CachedBodyRequest;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Envelope verification (design "Canonical payloads"; R3.2, R5.7, R5.8, R9.8): a well-signed
 * request passes and exposes the pairing's identity; every tampering or replay is refused as not
 * verified; an unknown or unpaired pairing is refused as not paired; the unsigned pairing calls
 * skip verification; every answer carries the server time.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceAppAuthInterceptorTest {

  private static final String PAIRING = "65f000000000000000000a01";
  private static final String SHOP = "65f000000000000000000b01";
  private static final String EMPLOYEE = "65f000000000000000000c01";
  private static final String NONCE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final Instant NOW = Instant.parse("2025-04-03T09:12:00Z");
  private static final String PUNCHES = AttendanceAppAuthInterceptor.PATH_PREFIX + "/punches";
  private static final String ME = AttendanceAppAuthInterceptor.PATH_PREFIX + "/me";

  @Mock private DevicePairingRepository pairingRepository;
  @Mock private AppNonceRepository nonceRepository;
  @Mock private MetricsWrapper metrics;
  @Mock private AppPunchHealthService health;
  @InjectMocks private AttendanceAppAuthInterceptor interceptor;

  private KeyPair deviceKey;
  private DevicePairing pairing;

  @BeforeEach
  void setUp() throws GeneralSecurityException {
    interceptor.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    deviceKey = p256();
    pairing = new DevicePairing();
    pairing.setId(PAIRING);
    pairing.setShopId(SHOP);
    pairing.setEmployeeId(EMPLOYEE);
    pairing.setStatus(PairingStatus.ACTIVE);
    pairing.setDevicePublicKey(spki(deviceKey));
    pairing.setPunchPublicKey(spki(p256()));
  }

  // --- success ---

  @Test
  void validPostPassesAndExposesThePairingIdentity() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    byte[] body = "{\"punchId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    HttpServletRequest request = signed("POST", PUNCHES, null, body, NOW.getEpochSecond(), NONCE,
        deviceKey);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertTrue(interceptor.preHandle(request, response, null));

    assertEquals(PAIRING, request.getAttribute("pairingId"));
    assertEquals(SHOP, request.getAttribute("shopId"));
    assertEquals(EMPLOYEE, request.getAttribute("employeeId"));
    AppRequestContext context = AppRequestContext.require(request);
    assertEquals(PAIRING, context.pairingId());
    assertEquals(SHOP, context.shopId());
    assertEquals(EMPLOYEE, context.employeeId());
    assertSame(pairing, context.pairing());
    assertEquals(Long.toString(NOW.getEpochSecond()),
        response.getHeader(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME));

    ArgumentCaptor<AppNonce> saved = ArgumentCaptor.forClass(AppNonce.class);
    verify(nonceRepository).save(saved.capture());
    assertEquals(PAIRING, saved.getValue().getPairingId());
    assertEquals(NONCE, saved.getValue().getNonce());
    assertEquals(NOW, saved.getValue().getSeenAt());
    verifyNoInteractions(metrics);
    verifyNoInteractions(health);
    // the controller can still read the body after the interceptor hashed it
    assertArrayEquals(body, request.getInputStream().readAllBytes());
  }

  @Test
  void validGetWithQueryPasses() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    HttpServletRequest request = signed("GET", AttendanceAppAuthInterceptor.PATH_PREFIX + "/punches",
        "from=2025-03-04&to=2025-04-03", null, NOW.getEpochSecond(), NONCE, deviceKey);

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
    assertNotNull(request.getAttribute(AppRequestContext.ATTRIBUTE));
  }

  @Test
  void timestampExactlyOnTheWindowEdgeIsAccepted() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    long edge = NOW.minus(AttendanceAppAuthInterceptor.MAX_CLOCK_SKEW).getEpochSecond();
    HttpServletRequest request = signed("GET", ME, null, null, edge, NONCE, deviceKey);

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
  }

  // --- not verified ---

  @Test
  void timestampOutsideTenMinutesIsNotVerified() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    long late = NOW.plus(Duration.ofMinutes(10).plusSeconds(1)).getEpochSecond();
    HttpServletRequest request = signed("GET", ME, null, null, late, NONCE, deviceKey);

    assertNotVerified(request);
  }

  @Test
  void reusedNonceIsNotVerified() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    when(nonceRepository.save(any())).thenThrow(new DuplicateKeyException("seen"));
    HttpServletRequest request = signed("GET", ME, null, null, NOW.getEpochSecond(), NONCE,
        deviceKey);

    assertNotVerified(request);
  }

  @Test
  void signatureFromAnotherKeyIsNotVerified() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    HttpServletRequest request = signed("GET", ME, null, null, NOW.getEpochSecond(), NONCE,
        p256());

    assertNotVerified(request);
    verify(nonceRepository, never()).save(any());
  }

  @Test
  void tamperedBodyIsNotVerified() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    byte[] signedBody = "{\"offline\":0}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest raw = request("POST", PUNCHES, null,
        "{\"offline\":1}".getBytes(StandardCharsets.UTF_8));
    sign(raw, PUNCHES, null, signedBody, NOW.getEpochSecond(), NONCE, deviceKey);

    assertNotVerified(wrap(raw));
  }

  @Test
  void tamperedPathOrQueryIsNotVerified() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    // signed for /me, sent to /punches
    MockHttpServletRequest raw = request("GET", PUNCHES, "from=a&to=b", null);
    sign(raw, ME, "from=a&to=b", null, NOW.getEpochSecond(), NONCE, deviceKey);

    assertNotVerified(wrap(raw));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      AttendanceAppAuthInterceptor.HEADER_TIMESTAMP,
      AttendanceAppAuthInterceptor.HEADER_NONCE,
      AttendanceAppAuthInterceptor.HEADER_SIGNATURE})
  void missingEnvelopeHeaderIsNotVerified(String header) throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    MockHttpServletRequest raw = request("GET", ME, null, null);
    sign(raw, ME, null, null, NOW.getEpochSecond(), NONCE, deviceKey);
    raw.removeHeader(header);

    assertNotVerified(wrap(raw));
  }

  @Test
  void postWhoseBodyWasNotBufferedIsNotVerified() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest raw = request("POST", PUNCHES, null, body);
    sign(raw, PUNCHES, null, body, NOW.getEpochSecond(), NONCE, deviceKey);

    // not wrapped by the filter: the body cannot be hashed safely
    assertNotVerified(raw);
  }

  // --- not paired ---

  @Test
  void unknownPairingIsNotPaired() {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.empty());
    MockHttpServletRequest raw = request("GET", ME, null, null);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, PAIRING);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(wrap(raw), response, null));

    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
    assertEquals("This phone is no longer paired. Ask your manager.", e.getMessage());
    assertNotNull(response.getHeader(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME));
    verifyNoInteractions(nonceRepository);
    verifySignatureFailed("unknown");
  }

  @Test
  void unpairedPairingIsNotPairedEvenWithAValidSignature() throws Exception {
    pairing.setStatus(PairingStatus.UNPAIRED);
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    HttpServletRequest request = signed("GET", ME, null, null, NOW.getEpochSecond(), NONCE,
        deviceKey);

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
    assertNull(request.getAttribute(AppRequestContext.ATTRIBUTE));
    verifyNoInteractions(nonceRepository);
    verifySignatureFailed(SHOP);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-an-object-id", "65F000000000000000000A01"})
  void missingOrMalformedPairingHeaderIsNotPairedWithoutALookup(String header) {
    MockHttpServletRequest raw = request("GET", ME, null, null);
    if (!header.isEmpty()) {
      raw.addHeader(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, header);
    }

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(wrap(raw), new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
    verifyNoInteractions(pairingRepository, nonceRepository);
  }

  // --- two credentials (worker-app R9.1) ---

  /**
   * A signed device request that also carries a Bearer token is refused as two credentials
   * before the status, envelope or handler is looked at; even a perfectly signed one. One
   * {@code signature_failed} on the shop, nothing else touched.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Bearer eyJhbGciOi.token.sig", "Basic dXNlcjpwYXNz", "x"})
  void aSignedRequestWithAnAuthorizationHeaderIsRefusedAsTwoCredentials(String authorization)
      throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    byte[] body = "{\"punchId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest raw = request("POST", PUNCHES, null, body);
    sign(raw, PUNCHES, null, body, NOW.getEpochSecond(), NONCE, deviceKey);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_AUTHORIZATION, authorization);
    HttpServletRequest request = wrap(raw);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, response, null));

    assertEquals(ErrorCode.HR_TWO_CREDENTIALS, e.getErrorCode());
    assertEquals("A request may carry a login or a phone signature, not both.", e.getMessage());
    assertNull(request.getAttribute(AppRequestContext.ATTRIBUTE));
    assertNull(request.getAttribute("shopId"));
    assertNull(request.getAttribute("employeeId"));
    assertNull(request.getAttribute("pairingId"));
    assertEquals(Long.toString(NOW.getEpochSecond()),
        response.getHeader(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME));
    verifyNoInteractions(nonceRepository);
    verifySignatureFailed(SHOP);
  }

  @ParameterizedTest
  @ValueSource(strings = {ME, PUNCHES,
      AttendanceAppAuthInterceptor.PATH_PREFIX + "/consent",
      AttendanceAppAuthInterceptor.PATH_PREFIX + "/pairings/me/unpair"})
  void everySignedDevicePathRefusesAnAuthorizationHeader(String path) {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    MockHttpServletRequest raw = request("POST", path, null, new byte[0]);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, PAIRING);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_AUTHORIZATION, "Bearer t");

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(wrap(raw), new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.HR_TWO_CREDENTIALS, e.getErrorCode());
    verifyNoInteractions(nonceRepository);
    verifySignatureFailed(SHOP);
  }

  /** Two credentials wins over "not paired": the mix is the bug to surface, whatever the pairing. */
  @Test
  void anAuthorizationHeaderWithoutAPairingIsStillTwoCredentials() {
    MockHttpServletRequest raw = request("GET", ME, null, null);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_AUTHORIZATION, "Bearer t");

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(wrap(raw), new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.HR_TWO_CREDENTIALS, e.getErrorCode());
    verifyNoInteractions(pairingRepository, nonceRepository);
    verifySignatureFailed("unknown");
  }

  /** A blank header is no credential: the request is judged on its envelope as before. */
  @Test
  void aBlankAuthorizationHeaderIsIgnored() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    MockHttpServletRequest raw = request("GET", ME, null, null);
    sign(raw, ME, null, null, NOW.getEpochSecond(), NONCE, deviceKey);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_AUTHORIZATION, "  ");

    assertTrue(interceptor.preHandle(wrap(raw), new MockHttpServletResponse(), null));
    verifyNoInteractions(metrics);
  }

  /** The unsigned consent read carries no signature, so a token there is not "two credentials". */
  @Test
  void theUnsignedConsentPathIsNotConcernedByTheAuthorizationRule() throws Exception {
    MockHttpServletRequest raw = request("GET", "/api/v1/hr/attendance-app/consent/latest", null,
        null);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_AUTHORIZATION, "Bearer t");

    assertTrue(interceptor.preHandle(wrap(raw), new MockHttpServletResponse(), null));
    verifyNoInteractions(pairingRepository, nonceRepository, metrics);
  }

  // --- allow-list and server time ---

  @Test
  void theUnsignedConsentPathSkipsVerificationButGetsServerTime() throws Exception {
    MockHttpServletRequest raw = request("GET", "/api/v1/hr/attendance-app/consent/latest", null,
        null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertTrue(interceptor.preHandle(wrap(raw), response, null));

    assertEquals(Long.toString(NOW.getEpochSecond()),
        response.getHeader(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME));
    assertNull(raw.getAttribute(AppRequestContext.ATTRIBUTE));
    verifyNoInteractions(pairingRepository, nonceRepository, metrics);
  }

  /**
   * Only the exact consent path is unsigned; a variant of it, and the removed handshake paths
   * (worker-app D11: no longer allow-listed, were they ever to reach this interceptor), are verified
   * like any device call.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "/api/v1/hr/attendance-app/consent/latest;x=1",
      "/api/v1/hr/attendance-app/consent/%6catest",
      "/api/v1/hr/attendance-app/consent/latest/../me",
      "/api/v1/hr/attendance-app/pairings/claim",
      "/api/v1/hr/attendance-app/pairings/complete",
      "/api/v1/hr/attendance-app/pairings/abandon",
      "/api/v1/hr/attendance-app/pairings/me/unpair"})
  void variantsOfTheUnsignedPathAndTheRemovedHandshakeAreVerified(String path) {
    MockHttpServletRequest raw = request("POST", path, null, new byte[0]);

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(wrap(raw), new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
  }

  @Test
  void refusalsCarryServerTimeToo() throws Exception {
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    HttpServletRequest request = signed("GET", ME, null, null, NOW.getEpochSecond(), NONCE,
        p256());
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThrows(BaseException.class, () -> interceptor.preHandle(request, response, null));
    assertEquals(Long.toString(NOW.getEpochSecond()),
        response.getHeader(AttendanceAppAuthInterceptor.HEADER_SERVER_TIME));
  }

  @Test
  void healthIsNotADevicePath() {
    assertFalse(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS.stream()
        .anyMatch(p -> p.equals(AttendanceAppAuthInterceptor.HEALTH_PATH)));
    assertFalse(AttendanceAppAuthInterceptor.UNSIGNED_PATHS
        .contains(AttendanceAppAuthInterceptor.HEALTH_PATH));
  }

  /** Worker-app D11, DD4: the device pairing sub-tree is {@code /pairings/me/**} and nothing else. */
  @Test
  void theRemovedHandshakeIsNeitherADevicePathNorUnsigned() {
    assertEquals(Set.of(AttendanceAppAuthInterceptor.PATH_PREFIX + "/consent/latest"),
        AttendanceAppAuthInterceptor.UNSIGNED_PATHS);
    assertTrue(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS
        .contains(AttendanceAppAuthInterceptor.PATH_PREFIX + "/pairings/me/**"));
    PathPatternParser parser = new PathPatternParser();
    for (String removed : List.of("/pairings/claim", "/pairings/complete", "/pairings/abandon")) {
      String path = AttendanceAppAuthInterceptor.PATH_PREFIX + removed;
      assertFalse(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS.stream()
          .anyMatch(p -> parser.parse(p).matches(PathContainer.parsePath(path))),
          path + " is an ordinary route now");
    }
  }

  @Test
  void requireWithoutTheInterceptorIsNotPaired() {
    BaseException e = assertThrows(BaseException.class,
        () -> AppRequestContext.require(new MockHttpServletRequest("GET", ME)));
    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
  }

  // --- helpers ---

  private void assertNotVerified(HttpServletRequest request) {
    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED, e.getErrorCode());
    assertEquals(ErrorCode.HR_APP_NOT_VERIFIED.getMessage(), e.getMessage());
    assertNull(request.getAttribute(AppRequestContext.ATTRIBUTE));
    assertNull(request.getAttribute("shopId"));
    verifySignatureFailed(SHOP);
  }

  /** One metric increment and one failed attempt in the shop's health window (R10.4). */
  private void verifySignatureFailed(String shop) {
    verify(metrics).increment(eq(HrMetricsConstants.APP_PUNCHES_TOTAL), anyDouble(),
        eq("module"), eq(HrMetricsConstants.MODULE),
        eq(HrMetricsConstants.TAG_SHOP), eq(shop),
        eq(HrMetricsConstants.TAG_RESULT), eq(HrMetricsConstants.AppPunchResult.SIGNATURE_FAILED));
    verify(health).record("unknown".equals(shop) ? null : shop, true);
  }

  @Test
  void aHealthFailureDoesNotChangeTheRefusal() throws Exception {
    pairing.setStatus(PairingStatus.UNPAIRED);
    when(pairingRepository.findById(PAIRING)).thenReturn(Optional.of(pairing));
    doThrow(new RuntimeException("health down")).when(health).record(any(), anyBoolean());
    HttpServletRequest request = signed("GET", ME, null, null, NOW.getEpochSecond(), NONCE,
        deviceKey);

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.HR_APP_NOT_PAIRED, e.getErrorCode());
    verifySignatureFailed(SHOP);
  }

  /** A request signed with {@code key}, wrapped as the body-caching filter would. */
  private static HttpServletRequest signed(String method, String path, String query, byte[] body,
      long timestamp, String nonce, KeyPair key) throws Exception {
    MockHttpServletRequest raw = request(method, path, query, body);
    sign(raw, path, query, body, timestamp, nonce, key);
    return wrap(raw);
  }

  private static MockHttpServletRequest request(String method, String path, String query,
      byte[] body) {
    MockHttpServletRequest raw = new MockHttpServletRequest(method, path);
    raw.setQueryString(query);
    if (body != null) {
      raw.setContent(body);
      raw.setContentType("application/json");
    }
    return raw;
  }

  private static void sign(MockHttpServletRequest raw, String path, String query, byte[] body,
      long timestamp, String nonce, KeyPair key) throws GeneralSecurityException {
    byte[] payload = AppSignatures.requestEnvelope(
        new EnvelopeFields(PAIRING, raw.getMethod(), path, query, timestamp, nonce, body));
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key.getPrivate());
    signer.update(payload);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, PAIRING);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_TIMESTAMP, Long.toString(timestamp));
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_NONCE, nonce);
    raw.addHeader(AttendanceAppAuthInterceptor.HEADER_SIGNATURE,
        Base64.getEncoder().encodeToString(signer.sign()));
  }

  /** Runs the real filter so the request is the wrapper the interceptor expects. */
  private static HttpServletRequest wrap(MockHttpServletRequest raw) {
    MockFilterChain chain = new MockFilterChain();
    try {
      new AttendanceAppBodyCachingFilter().doFilter(raw, new MockHttpServletResponse(), chain);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();
    assertNotNull(CachedBodyRequest.find(wrapped), "filter did not wrap " + raw.getRequestURI());
    return wrapped;
  }

  private static KeyPair p256() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String spki(KeyPair key) {
    return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
  }
}
