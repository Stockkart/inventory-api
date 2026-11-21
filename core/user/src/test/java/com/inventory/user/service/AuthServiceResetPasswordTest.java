package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserToken;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.request.ResetPasswordRequest;
import com.inventory.user.validation.AuthValidator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A password reset logs out every session of the account, cached ones included. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceResetPasswordTest {

  private static final String RESET_TOKEN = "reset-token";

  @Mock private UserAccountRepository userAccountRepository;
  @Mock private MetricsWrapper metrics;
  @Spy private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  @Spy private AuthValidator authValidator = new AuthValidator();
  @Spy private AuthCache authCache = new AuthCache(true, 60, 1000);
  @InjectMocks private AuthService authService;

  private final UserAccount account = new UserAccount();

  @BeforeEach
  void setUp() {
    account.setUserId("u-1");
    account.setEmail("owner@shop.example");
    account.setRole(UserRole.OWNER);
    account.setActive(true);
    account.setPassword(passwordEncoder.encode("old-password"));
    account.setPasswordResetToken(RESET_TOKEN);
    account.setPasswordResetTokenExpiresAt(Instant.now().plusSeconds(600));
    account.addToken(token("laptop-token", "laptop"), Instant.now());
    account.addToken(token("phone-token", "phone"), Instant.now());
    when(userAccountRepository.findByPasswordResetToken(RESET_TOKEN)).thenReturn(Optional.of(account));
  }

  private static UserToken token(String accessToken, String deviceId) {
    Instant now = Instant.now();
    return new UserToken(accessToken, accessToken + "-refresh", deviceId, now, now.plusSeconds(3600));
  }

  private static ResetPasswordRequest request(String token, String next) {
    ResetPasswordRequest r = new ResetPasswordRequest();
    r.setToken(token);
    r.setNewPassword(next);
    return r;
  }

  @Test
  void aResetLogsOutEverySession() {
    authCache.putAccount("phone-token", account, account.getTokens().get(1));

    authService.resetPassword(request(RESET_TOKEN, "brand-new-password"));

    assertTrue(passwordEncoder.matches("brand-new-password", account.getPassword()));
    assertTrue(account.getTokens().isEmpty());
    verify(userAccountRepository).save(account);
    verify(authCache).evictUser("u-1");
    assertNull(authCache.getAccount("phone-token"));
  }

  @Test
  void anExpiredResetTokenKeepsEverySession() {
    account.setPasswordResetTokenExpiresAt(Instant.now().minusSeconds(1));

    assertThrows(ValidationException.class,
        () -> authService.resetPassword(request(RESET_TOKEN, "brand-new-password")));

    assertTrue(passwordEncoder.matches("old-password", account.getPassword()));
    assertEquals(2, account.getTokens().size());
    verify(authCache, never()).evictUser(any());
  }
}
