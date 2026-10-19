package com.inventory.user.service;

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
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.request.ChangePasswordRequest;
import com.inventory.user.validation.AuthValidator;
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

/** Change password acts on the signed-in account only and needs its current password. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceChangePasswordTest {

  private static final String CURRENT = "current-password";

  @Mock private UserAccountRepository userAccountRepository;
  @Mock private MetricsWrapper metrics;
  @Spy private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  @Spy private AuthValidator authValidator = new AuthValidator();
  @InjectMocks private AuthService authService;

  private final UserAccount account = new UserAccount();

  @BeforeEach
  void setUp() {
    account.setUserId("staff-1");
    account.setEmail("staff@stockkart.example");
    account.setRole(UserRole.SUPER_USER);
    account.setActive(true);
    account.setPassword(passwordEncoder.encode(CURRENT));
    when(userAccountRepository.findById("staff-1")).thenReturn(Optional.of(account));
  }

  private ChangePasswordRequest request(String email, String current, String next) {
    ChangePasswordRequest r = new ChangePasswordRequest();
    r.setEmail(email);
    r.setCurrentPassword(current);
    r.setPassword(next);
    return r;
  }

  @Test
  void theRightCurrentPasswordChangesIt() {
    authService.changePassword("staff-1", request(null, CURRENT, "brand-new-password"));

    assertTrue(passwordEncoder.matches("brand-new-password", account.getPassword()));
    verify(userAccountRepository).save(account);
  }

  @Test
  void aWrongCurrentPasswordChangesNothing() {
    assertThrows(ValidationException.class,
        () -> authService.changePassword("staff-1", request(null, "guess", "brand-new-password")));
    verify(userAccountRepository, never()).save(any());
  }

  @Test
  void aMissingCurrentPasswordChangesNothing() {
    assertThrows(ValidationException.class,
        () -> authService.changePassword("staff-1", request(null, null, "brand-new-password")));
    verify(userAccountRepository, never()).save(any());
  }

  @Test
  void anotherAccountsEmailIsRejected() {
    // the old public endpoint changed whichever account the body named
    assertThrows(ValidationException.class, () -> authService.changePassword(
        "staff-1", request("victim@example.com", CURRENT, "brand-new-password")));
    verify(userAccountRepository, never()).findByEmail(any());
    verify(userAccountRepository, never()).save(any());
  }

  @Test
  void anAccountWithoutAPasswordMustUseForgotPassword() {
    account.setPassword(null);
    assertThrows(ValidationException.class,
        () -> authService.changePassword("staff-1", request(null, CURRENT, "brand-new-password")));
    verify(userAccountRepository, never()).save(any());
  }
}
