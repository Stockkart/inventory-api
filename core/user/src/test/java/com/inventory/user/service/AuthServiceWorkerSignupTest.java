package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.notifications.api.EmailTemplate;
import com.inventory.notifications.api.Messaging;
import com.inventory.user.api.port.WorkerAppAuthPort;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserToken;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.externalservice.GoogleTokenVerifier;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.request.AuthSource;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.request.SignupRequest;
import com.inventory.user.rest.dto.response.LoginResponse;
import com.inventory.user.rest.dto.response.SignupResponse;
import com.inventory.user.utils.constants.UserMetricsConstants;
import com.inventory.user.validation.AuthValidator;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Worker-app R1.4, R1.5, R1.8: a WORKER account from the app starts with no shop, no membership and
 * no employee; sign-up, sign-in and sign-out are counted (and the first two e-mailed) with their
 * {@code source}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceWorkerSignupTest {

  private static final String EMAIL = "priya@example.com";
  private static final String PASSWORD = "long-enough-password";

  @Mock private UserAccountRepository userAccountRepository;
  @Mock private GoogleTokenVerifier googleTokenVerifier;
  @Mock private TokenValidationService tokenValidationService;
  @Mock private ShopServiceAdapter shopServiceAdapter;
  @Mock private Messaging messagingService;
  @Mock private MetricsWrapper metrics;
  @Spy private UserMapper userMapper = Mappers.getMapper(UserMapper.class);
  @Spy private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  @Spy private AuthValidator authValidator = new AuthValidator();
  @InjectMocks private AuthService authService;

  private SignupRequest workerEmailSignup() {
    SignupRequest r = new SignupRequest();
    r.setName("Priya Sharma");
    r.setEmail(EMAIL);
    r.setPassword(PASSWORD);
    r.setPhone("+91 98765 43210");
    r.setRole(UserRole.WORKER);
    r.setSource("worker-app");
    return r;
  }

  private SignupRequest workerGoogleSignup() {
    SignupRequest r = new SignupRequest();
    r.setIdToken("google-id-token");
    r.setSignupType("google");
    r.setRole(UserRole.WORKER);
    r.setPhone("9876543210");
    r.setSource("worker-app");
    return r;
  }

  private void verifyAuthCounted(String operation, String outcome, String source) {
    verify(metrics)
        .record(
            eq(UserMetricsConstants.AUTH_TOTAL),
            eq(1.0),
            eq("module"),
            eq(UserMetricsConstants.MODULE),
            eq("operation"),
            eq(operation),
            eq("outcome"),
            eq(outcome),
            eq("source"),
            eq(source));
  }

  @Test
  void workerSignupCreatesAnAccountWithNoShop() {
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    SignupResponse response = authService.signup(workerEmailSignup());

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(userAccountRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    UserAccount account = saved.getValue();
    assertEquals(UserRole.WORKER, account.getRole());
    assertNull(account.getShopId(), "a worker has no shop until a manager approves");
    assertEquals("9876543210", account.getPhone());
    assertEquals("Priya Sharma", account.getName());
    assertEquals(UserRole.WORKER, response.getUser().getRole());
    assertNull(response.getUser().getShopId());
    assertNotNull(response.getAccessToken());
    // no shop lookup, so no shop block and no membership touched
    verifyNoInteractions(shopServiceAdapter);
    verifyAuthCounted("signup", "success", AuthSource.WORKER_APP);
  }

  @Test
  void workerSignupPassesTheSourceToTheWelcomeEmail() {
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    authService.signup(workerEmailSignup());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(messagingService)
        .sendEmailWithTemplate(eq(EMAIL), eq(EmailTemplate.WELCOME_SIGNUP), vars.capture());
    assertEquals(AuthSource.WORKER_APP, vars.getValue().get("source"));
    assertEquals("Priya Sharma", vars.getValue().get("userName"));
  }

  @Test
  void webSignupIsCountedAsWebWhenNoSourceIsSent() {
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
    SignupRequest r = workerEmailSignup();
    r.setRole(UserRole.OWNER);
    r.setSource(null);

    authService.signup(r);

    verifyAuthCounted("signup", "success", AuthSource.WEB);
  }

  @Test
  void duplicateEmailIsRefusedWithTheWorkerTextAndCountedUnderTheSource() {
    // R1.5: "An account with this email already exists. Sign in instead."
    UserAccount existing = new UserAccount();
    existing.setEmail(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    ValidationException e =
        assertThrows(ValidationException.class, () -> authService.signup(workerEmailSignup()));

    assertEquals("An account with this email already exists. Sign in instead.", e.getMessage());
    assertEquals(AuthService.WORKER_DUPLICATE_EMAIL_MESSAGE, e.getMessage());
    verify(userAccountRepository, never()).save(any());
    verifyAuthCounted("signup", "error", AuthSource.WORKER_APP);
  }

  @Test
  void duplicateEmailOnTheWorkerGooglePathGetsTheSameWorkerText() {
    when(googleTokenVerifier.verifyToken("google-id-token"))
        .thenReturn(Map.of("email", EMAIL, "name", "Priya Sharma"));
    when(googleTokenVerifier.getEmail(anyMap())).thenReturn(EMAIL);
    when(googleTokenVerifier.getName(anyMap())).thenReturn("Priya Sharma");
    UserAccount existing = new UserAccount();
    existing.setEmail(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    ValidationException e =
        assertThrows(ValidationException.class, () -> authService.signup(workerGoogleSignup()));

    assertEquals(AuthService.WORKER_DUPLICATE_EMAIL_MESSAGE, e.getMessage());
    verify(userAccountRepository, never()).save(any());
  }

  @Test
  void theWorkerRoleAloneIsEnoughForTheWorkerTextAndTheWebKeepsItsOwn() {
    UserAccount existing = new UserAccount();
    existing.setEmail(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    // role WORKER without a source
    SignupRequest workerNoSource = workerEmailSignup();
    workerNoSource.setSource(null);
    assertEquals(AuthService.WORKER_DUPLICATE_EMAIL_MESSAGE,
        assertThrows(ValidationException.class, () -> authService.signup(workerNoSource))
            .getMessage());

    // a staff sign-up from the web is unchanged
    SignupRequest web = workerEmailSignup();
    web.setRole(UserRole.OWNER);
    web.setSource(null);
    assertEquals("User with this email already exists",
        assertThrows(ValidationException.class, () -> authService.signup(web)).getMessage());
    verify(userAccountRepository, never()).save(any());
  }

  @Test
  void workerGoogleSignupWithoutAPhoneIsRefusedBeforeGoogleIsAsked() {
    SignupRequest r = workerGoogleSignup();
    r.setPhone(null);

    ValidationException e = assertThrows(ValidationException.class, () -> authService.signup(r));

    assertEquals("Phone is required", e.getMessage());
    verifyNoInteractions(googleTokenVerifier);
    verify(userAccountRepository, never()).save(any());
    verifyAuthCounted("signup", "error", AuthSource.WORKER_APP);
  }

  @Test
  void workerGoogleSignupWithAPhoneCreatesAWorkerWithNoShop() {
    when(googleTokenVerifier.verifyToken("google-id-token"))
        .thenReturn(Map.of("email", EMAIL, "name", "Priya Sharma"));
    when(googleTokenVerifier.getEmail(anyMap())).thenReturn(EMAIL);
    when(googleTokenVerifier.getName(anyMap())).thenReturn("Priya Sharma");
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    SignupResponse response = authService.signup(workerGoogleSignup());

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(userAccountRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    UserAccount account = saved.getValue();
    assertEquals(UserRole.WORKER, account.getRole());
    assertNull(account.getShopId());
    assertEquals("9876543210", account.getPhone());
    assertNull(account.getPassword());
    assertEquals(UserRole.WORKER, response.getUser().getRole());
    verifyAuthCounted("signup", "success", AuthSource.WORKER_APP);
  }

  @Test
  void aGoogleTokenForAnotherClientIsRefusedAsInvalidCredentialsOnSignup() {
    when(googleTokenVerifier.verifyToken("google-id-token"))
        .thenThrow(
            new AuthenticationException(
                ErrorCode.INVALID_CREDENTIALS, "Google ID token was not issued for this application"));

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> authService.signup(workerGoogleSignup()));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
    verify(userAccountRepository, never()).save(any());
    verifyAuthCounted("signup", "error", AuthSource.WORKER_APP);
  }

  @Test
  void workerLoginIsCountedAndEmailedWithTheSource() {
    UserAccount account = new UserAccount();
    account.setUserId("worker-1");
    account.setName("Priya Sharma");
    account.setEmail(EMAIL);
    account.setPassword(passwordEncoder.encode(PASSWORD));
    account.setRole(UserRole.WORKER);
    account.setShopId(null);
    account.setActive(true);
    account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
    LoginRequest request = new LoginRequest();
    request.setEmail(EMAIL);
    request.setPassword(PASSWORD);
    request.setSource("worker-app");

    LoginResponse response = authService.login(request);

    assertEquals(UserRole.WORKER, response.getUser().getRole());
    assertNull(response.getUser().getShopId());
    assertNull(response.getShop());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(messagingService)
        .sendEmailWithTemplate(eq(EMAIL), eq(EmailTemplate.LOGIN_NOTIFICATION), vars.capture());
    assertEquals(AuthSource.WORKER_APP, vars.getValue().get("source"));
    verifyAuthCounted("login", "success", AuthSource.WORKER_APP);
  }

  @Test
  void aWorkerLogoutIsCountedUnderTheSourceTheSessionWasOpenedWith() {
    UserAccount account = new UserAccount();
    account.setUserId("worker-1");
    account.setName("Priya Sharma");
    account.setEmail(EMAIL);
    account.setPassword(passwordEncoder.encode(PASSWORD));
    account.setRole(UserRole.WORKER);
    account.setActive(true);
    account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
    when(userAccountRepository.findById("worker-1")).thenReturn(Optional.of(account));
    LoginRequest request = new LoginRequest();
    request.setEmail(EMAIL);
    request.setPassword(PASSWORD);
    request.setSource("worker-app");
    String token = authService.login(request).getAccessToken();
    assertEquals(AuthSource.WORKER_APP, account.getTokens().get(0).getSource(),
        "the minted token remembers which client opened the session");

    authService.logout("worker-1", token);

    verifyAuthCounted("logout", "success", AuthSource.WORKER_APP);
    assertEquals(0, account.getTokens().size(), "the session is gone");
  }

  @Test
  void aLogoutOfATokenWithoutASourceIsCountedAsTheWeb() {
    UserAccount account = new UserAccount();
    account.setUserId("owner-1");
    account.setEmail(EMAIL);
    account.setRole(UserRole.OWNER);
    account.setActive(true);
    UserToken old = new UserToken("old-token", "old-refresh", "device-1", Instant.now(),
        Instant.now().plusSeconds(3600));
    account.getTokens().add(old);
    when(userAccountRepository.findById("owner-1")).thenReturn(Optional.of(account));

    authService.logout("owner-1", "old-token");

    verifyAuthCounted("logout", "success", AuthSource.WEB);
  }

  @Test
  void aFailedWorkerLoginIsCountedUnderTheSource() {
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
    LoginRequest request = new LoginRequest();
    request.setEmail(EMAIL);
    request.setPassword(PASSWORD);
    request.setSource("worker-app");

    assertThrows(AuthenticationException.class, () -> authService.login(request));

    verifyAuthCounted("login", "error", AuthSource.WORKER_APP);
  }

  // ---- worker-app R1.10 (DD20): Google login with no account has its own code

  private LoginRequest googleLogin() {
    LoginRequest request = new LoginRequest();
    request.setIdToken("google-id-token");
    request.setLoginType("google");
    request.setSource("worker-app");
    return request;
  }

  @Test
  void aGoogleLoginWithNoAccountAnswersOauthAccountNotFoundWithTheWebsText() {
    when(googleTokenVerifier.verifyToken("google-id-token"))
        .thenReturn(Map.of("email", EMAIL, "name", "Priya Sharma"));
    when(googleTokenVerifier.getEmail(anyMap())).thenReturn(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> authService.login(googleLogin()));

    assertEquals(ErrorCode.OAUTH_ACCOUNT_NOT_FOUND, e.getErrorCode());
    assertEquals(4004, e.getErrorCode().getCode());
    assertEquals(401, e.getErrorCode().getHttpStatus().value());
    assertEquals("No account found for this Google account. Please sign up first.",
        e.getMessage(), "the enum's text, byte for byte what the web showed before");
    verify(userAccountRepository, never()).save(any());
    verifyAuthCounted("login", "error", AuthSource.WORKER_APP);
  }

  @Test
  void aGoogleLoginForAnExistingAccountStillSignsIn() {
    UserAccount account = new UserAccount();
    account.setUserId("worker-1");
    account.setName("Priya Sharma");
    account.setEmail(EMAIL);
    account.setRole(UserRole.WORKER);
    account.setActive(true);
    account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(googleTokenVerifier.verifyToken("google-id-token"))
        .thenReturn(Map.of("email", EMAIL, "name", "Priya Sharma"));
    when(googleTokenVerifier.getEmail(anyMap())).thenReturn(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

    LoginResponse response = authService.login(googleLogin());

    assertEquals("worker-1", response.getUser().getUserId());
    assertNotNull(response.getAccessToken());
    verifyAuthCounted("login", "success", AuthSource.WORKER_APP);
  }

  @Test
  void aWrongPasswordStaysInvalidCredentials() {
    UserAccount account = new UserAccount();
    account.setUserId("worker-1");
    account.setEmail(EMAIL);
    account.setPassword(passwordEncoder.encode(PASSWORD));
    account.setRole(UserRole.WORKER);
    account.setActive(true);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
    LoginRequest request = new LoginRequest();
    request.setEmail(EMAIL);
    request.setPassword("not-the-password");
    request.setSource("worker-app");

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> authService.login(request));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
    assertEquals("Invalid email or password", e.getMessage());
  }

  @Test
  void aGoogleTokenForAnotherClientIsRefusedAsInvalidCredentialsOnLogin() {
    when(googleTokenVerifier.verifyToken("google-id-token"))
        .thenThrow(new AuthenticationException(
            ErrorCode.INVALID_CREDENTIALS, "Google ID token was not issued for this application"));

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> authService.login(googleLogin()));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode(), "DD8: a foreign aud is not 'no account'");
    verify(userAccountRepository, never()).findByEmail(any());
  }

  // ---- worker-app R14.1, R14.2: the per-IP sign-up limit and the worker counters through the port

  private WorkerAppAuthPort wirePort() {
    WorkerAppAuthPort port = org.mockito.Mockito.mock(WorkerAppAuthPort.class);
    when(port.admitSignup(any())).thenReturn(true);
    ReflectionTestUtils.setField(authService, "workerAppAuth", port);
    return port;
  }

  @Test
  void anAppSignupOverThePerIpLimitIsRefusedBeforeAnythingIsReadAndCountedAsAnError() {
    WorkerAppAuthPort port = wirePort();
    when(port.admitSignup("203.0.113.9")).thenReturn(false);

    BaseException e = assertThrows(BaseException.class,
        () -> authService.signup(workerEmailSignup(), "203.0.113.9"));

    assertEquals(ErrorCode.HR_TOO_MANY_ATTEMPTS, e.getErrorCode());
    assertEquals("Too many attempts, try again later", e.getMessage());
    verifyNoInteractions(userAccountRepository, messagingService, googleTokenVerifier);
    verify(port, never()).workerSignedUp();
    verifyAuthCounted("signup", "error", AuthSource.WORKER_APP);
  }

  @Test
  void anAdmittedAppSignupIsKeyedByTheClientIpAndCountedOnce() {
    WorkerAppAuthPort port = wirePort();
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    authService.signup(workerEmailSignup(), "203.0.113.9");

    verify(port).admitSignup("203.0.113.9");
    verify(port, org.mockito.Mockito.times(1)).workerSignedUp();
    verifyAuthCounted("signup", "success", AuthSource.WORKER_APP);
  }

  @Test
  void aWebSignupNeverTouchesThePerIpLimitAndAStaffAccountIsNotAWorkerSignup() {
    WorkerAppAuthPort port = wirePort();
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
    SignupRequest r = workerEmailSignup();
    r.setRole(UserRole.OWNER);
    r.setSource(null);

    authService.signup(r, "203.0.113.9");

    verify(port, never()).admitSignup(any());
    verify(port, never()).workerSignedUp();
  }

  @Test
  void aWorkerSignupFromTheWebIsStillAWorkerSignup() {
    WorkerAppAuthPort port = wirePort();
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
    SignupRequest r = workerEmailSignup();
    r.setSource("web");

    authService.signup(r, "203.0.113.9");

    verify(port, never()).admitSignup(any());
    verify(port).workerSignedUp();
  }

  @Test
  void aRefusedAppSignupDoesNotCountAsAWorkerSignup() {
    WorkerAppAuthPort port = wirePort();
    UserAccount existing = new UserAccount();
    existing.setEmail(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    assertThrows(ValidationException.class,
        () -> authService.signup(workerEmailSignup(), "203.0.113.9"));

    verify(port).admitSignup("203.0.113.9");
    verify(port, never()).workerSignedUp();
  }

  @Test
  void withoutAPortTheSignupStillWorks() {
    ReflectionTestUtils.setField(authService, "workerAppAuth", null);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    SignupResponse response = authService.signup(workerEmailSignup(), "203.0.113.9");

    assertEquals(UserRole.WORKER, response.getUser().getRole());
  }

  @Test
  void appLoginsAreCountedByResultAndWebLoginsAreNot() {
    WorkerAppAuthPort port = wirePort();
    UserAccount account = new UserAccount();
    account.setUserId("worker-1");
    account.setName("Priya Sharma");
    account.setEmail(EMAIL);
    account.setPassword(passwordEncoder.encode(PASSWORD));
    account.setRole(UserRole.WORKER);
    account.setActive(true);
    account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

    LoginRequest ok = new LoginRequest();
    ok.setEmail(EMAIL);
    ok.setPassword(PASSWORD);
    ok.setSource("worker-app");
    authService.login(ok);
    verify(port).appLogin(true);

    LoginRequest wrong = new LoginRequest();
    wrong.setEmail(EMAIL);
    wrong.setPassword("not-the-password");
    wrong.setSource("worker-app");
    assertThrows(AuthenticationException.class, () -> authService.login(wrong));
    verify(port).appLogin(false);

    LoginRequest web = new LoginRequest();
    web.setEmail(EMAIL);
    web.setPassword(PASSWORD);
    authService.login(web);
    verify(port, org.mockito.Mockito.times(1)).appLogin(true);
    verify(port, org.mockito.Mockito.times(1)).appLogin(false);
  }

  @Test
  void aCounterFailureNeverFailsTheSignIn() {
    WorkerAppAuthPort port = wirePort();
    org.mockito.Mockito.doThrow(new IllegalStateException("meter down")).when(port).appLogin(true);
    UserAccount account = new UserAccount();
    account.setUserId("worker-1");
    account.setEmail(EMAIL);
    account.setPassword(passwordEncoder.encode(PASSWORD));
    account.setRole(UserRole.WORKER);
    account.setActive(true);
    account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
    LoginRequest request = new LoginRequest();
    request.setEmail(EMAIL);
    request.setPassword(PASSWORD);
    request.setSource("worker-app");

    LoginResponse response = authService.login(request);

    assertNotNull(response.getAccessToken());
  }
}
