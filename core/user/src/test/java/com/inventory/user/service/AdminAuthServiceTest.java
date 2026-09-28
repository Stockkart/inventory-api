package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.AdminSession;
import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.domain.repository.AdminSessionRepository;
import com.inventory.user.domain.repository.AdminUserRepository;
import com.inventory.user.domain.repository.AdminUserWriter;
import com.inventory.user.rest.dto.request.AdminChangePasswordRequest;
import com.inventory.user.rest.dto.request.AdminLoginRequest;
import com.inventory.user.rest.dto.response.AdminLoginResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");
  private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
  private static final String PASSWORD = "correct-horse-1";
  private static final String HASH = ENCODER.encode(PASSWORD);

  @Mock
  private AdminUserRepository adminUserRepository;

  @Mock
  private AdminSessionRepository sessionRepository;

  @Mock
  private AdminUserWriter adminUserWriter;

  @Spy
  private PasswordEncoder passwordEncoder = ENCODER;

  @InjectMocks
  private AdminAuthService service;

  @BeforeEach
  void fixClock() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void loginIssuesTwelveHourSessionAndStoresOnlyTheTokenHash() {
    when(adminUserRepository.findByEmail("ops@stockkart.in")).thenReturn(Optional.of(admin()));
    when(sessionRepository.save(any(AdminSession.class))).thenAnswer(i -> i.getArgument(0));

    AdminLoginResponse response = service.login(new AdminLoginRequest(" Ops@StockKart.in ", PASSWORD));

    ArgumentCaptor<AdminSession> saved = ArgumentCaptor.forClass(AdminSession.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getTokenHash())
        .isEqualTo(AdminCredentials.hashToken(response.getToken()))
        .isNotEqualTo(response.getToken());
    assertThat(response.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
    assertThat(response.getAdmin().getEmail()).isEqualTo("ops@stockkart.in");
    verify(adminUserWriter).recordSuccessfulLogin("a1", NOW);
  }

  @Test
  void unknownEmailAndWrongPasswordGiveTheSameMessage() {
    when(adminUserRepository.findByEmail("nobody@x.io")).thenReturn(Optional.empty());
    when(adminUserRepository.findByEmail("ops@stockkart.in")).thenReturn(Optional.of(admin()));

    assertThatThrownBy(() -> service.login(new AdminLoginRequest("nobody@x.io", PASSWORD)))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage(AdminAuthService.BAD_CREDENTIALS);
    assertThatThrownBy(() -> service.login(new AdminLoginRequest("ops@stockkart.in", "wrong-password")))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage(AdminAuthService.BAD_CREDENTIALS);
    verify(adminUserWriter).recordFailedLogin("a1", NOW, 5, Duration.ofMinutes(15));
  }

  @Test
  void attemptThatReachesTheLimitReportsTheLock() {
    when(adminUserRepository.findByEmail("ops@stockkart.in")).thenReturn(Optional.of(admin()));
    when(adminUserWriter.recordFailedLogin("a1", NOW, 5, Duration.ofMinutes(15))).thenReturn(true);

    assertThatThrownBy(() -> service.login(new AdminLoginRequest("ops@stockkart.in", "wrong-password")))
        .hasMessage(AdminAuthService.LOCKED);
  }

  @Test
  void lockedAccountIsRefusedEvenWithTheRightPasswordAndIsNotCounted() {
    AdminUser locked = admin();
    locked.setLockedUntil(NOW.plusSeconds(60));
    when(adminUserRepository.findByEmail("ops@stockkart.in")).thenReturn(Optional.of(locked));

    assertThatThrownBy(() -> service.login(new AdminLoginRequest("ops@stockkart.in", PASSWORD)))
        .hasMessage(AdminAuthService.LOCKED);
    verify(adminUserWriter, never()).recordFailedLogin(anyString(), any(), eq(5), any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void expiredLockLetsTheAdminIn() {
    AdminUser unlocked = admin();
    unlocked.setLockedUntil(NOW.minusSeconds(1));
    when(adminUserRepository.findByEmail("ops@stockkart.in")).thenReturn(Optional.of(unlocked));
    when(sessionRepository.save(any(AdminSession.class))).thenAnswer(i -> i.getArgument(0));

    assertThat(service.login(new AdminLoginRequest("ops@stockkart.in", PASSWORD)).getToken()).isNotBlank();
  }

  @Test
  void disabledAdminCannotSignIn() {
    AdminUser disabled = admin();
    disabled.setActive(false);
    when(adminUserRepository.findByEmail("ops@stockkart.in")).thenReturn(Optional.of(disabled));

    assertThatThrownBy(() -> service.login(new AdminLoginRequest("ops@stockkart.in", PASSWORD)))
        .isInstanceOf(AuthenticationException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void authenticateResolvesALiveSession() {
    AdminSession session = session(NOW.plusSeconds(60));
    when(sessionRepository.findByTokenHash(AdminCredentials.hashToken("tok"))).thenReturn(Optional.of(session));
    when(adminUserRepository.findById("a1")).thenReturn(Optional.of(admin()));

    AdminAuthentication auth = service.authenticate("tok");

    assertThat(auth.adminId()).isEqualTo("a1");
    assertThat(auth.session()).isSameAs(session);
  }

  @Test
  void expiredSessionIsDeletedAndRejected() {
    when(sessionRepository.findByTokenHash(AdminCredentials.hashToken("tok")))
        .thenReturn(Optional.of(session(NOW)));

    assertThatThrownBy(() -> service.authenticate("tok")).isInstanceOf(AuthenticationException.class);
    verify(sessionRepository).deleteById("s1");
  }

  @Test
  void unknownTokenAndDisabledAdminAreRejected() {
    when(sessionRepository.findByTokenHash(AdminCredentials.hashToken("shop-user-token"))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.authenticate("shop-user-token")).isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> service.authenticate(" ")).isInstanceOf(AuthenticationException.class);

    AdminUser disabled = admin();
    disabled.setActive(false);
    when(sessionRepository.findByTokenHash(AdminCredentials.hashToken("tok")))
        .thenReturn(Optional.of(session(NOW.plusSeconds(60))));
    when(adminUserRepository.findById("a1")).thenReturn(Optional.of(disabled));
    assertThatThrownBy(() -> service.authenticate("tok")).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void logoutDeletesOnlyTheCurrentSession() {
    service.logout(new AdminAuthentication(admin(), session(NOW.plusSeconds(60))));

    verify(sessionRepository).deleteById("s1");
  }

  @Test
  void changePasswordClearsTheFlagAndSignsOutOtherSessions() {
    AdminUser admin = admin();
    admin.setMustChangePassword(true);

    var response = service.changePassword(new AdminAuthentication(admin, session(NOW.plusSeconds(60))),
        new AdminChangePasswordRequest(PASSWORD, "a-brand-new-password"));

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(adminUserWriter).setPassword(eq("a1"), hash.capture(), eq(false), eq(NOW));
    assertThat(ENCODER.matches("a-brand-new-password", hash.getValue())).isTrue();
    verify(sessionRepository).deleteByAdminIdAndIdNot("a1", "s1");
    assertThat(response.isMustChangePassword()).isFalse();
  }

  @Test
  void changePasswordRejectsWrongCurrentShortAndUnchangedPasswords() {
    AdminAuthentication auth = new AdminAuthentication(admin(), session(NOW.plusSeconds(60)));

    assertThatThrownBy(() -> service.changePassword(auth, new AdminChangePasswordRequest("nope", "a-brand-new-password")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Current password");
    assertThatThrownBy(() -> service.changePassword(auth, new AdminChangePasswordRequest(PASSWORD, "short")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("10 to 128");
    assertThatThrownBy(() -> service.changePassword(auth, new AdminChangePasswordRequest(PASSWORD, PASSWORD)))
        .isInstanceOf(ValidationException.class).hasMessageContaining("different");
    verify(adminUserWriter, never()).setPassword(anyString(), anyString(), eq(false), any());
  }

  private static AdminUser admin() {
    return AdminUser.builder()
        .id("a1")
        .email("ops@stockkart.in")
        .name("Ops")
        .passwordHash(HASH)
        .active(true)
        .build();
  }

  private static AdminSession session(Instant expiresAt) {
    return AdminSession.builder().id("s1").adminId("a1").tokenHash("h").createdAt(NOW).expiresAt(expiresAt).build();
  }
}
