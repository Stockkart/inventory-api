package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.notifications.api.Messaging;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.externalservice.GoogleTokenVerifier;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.response.LoginResponse;
import com.inventory.user.validation.AuthValidator;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A4/A5: a Stockkart staff account (SUPER_USER, no shop) can log in and gets its role back. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceSuperUserLoginTest {

  private static final String EMAIL = "staff@stockkart.example";
  private static final String PASSWORD = "correct-horse-battery";

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

  private UserAccount superUserWithNoShop() {
    UserAccount account = new UserAccount();
    account.setUserId("staff-1");
    account.setName("Staff One");
    account.setEmail(EMAIL);
    account.setPassword(passwordEncoder.encode(PASSWORD));
    account.setRole(UserRole.SUPER_USER);
    account.setShopId(null);
    account.setActive(true);
    account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return account;
  }

  @Test
  void emailPasswordLoginWorksForASuperUserWithNoShop() throws Exception {
    UserAccount account = superUserWithNoShop();
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
    LoginRequest request = new LoginRequest();
    request.setEmail(EMAIL);
    request.setPassword(PASSWORD);

    LoginResponse response = authService.login(request);

    assertEquals(UserRole.SUPER_USER, response.getUser().getRole());
    assertNull(response.getUser().getShopId());
    assertNull(response.getShop());
    assertNotNull(response.getAccessToken());
    assertEquals(1, account.getTokens().size());
    verify(userAccountRepository).save(account);
    verifyNoInteractions(shopServiceAdapter);
    // HQ reads user.role from the JSON envelope
    String json = new ObjectMapper().findAndRegisterModules()
        .writeValueAsString(ApiResponse.success(response));
    assertTrue(json.contains("\"role\":\"SUPER_USER\""), json);
  }

  @Test
  void googleLoginWorksForASuperUserWithNoShop() {
    UserAccount account = superUserWithNoShop();
    account.setPassword(null);
    when(googleTokenVerifier.verifyToken("google-id-token")).thenReturn(Map.of("email", EMAIL));
    when(googleTokenVerifier.getEmail(anyMap())).thenReturn(EMAIL);
    when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
    LoginRequest request = new LoginRequest();
    request.setIdToken("google-id-token");
    request.setLoginType("google");

    LoginResponse response = authService.login(request);

    assertEquals(UserRole.SUPER_USER, response.getUser().getRole());
    assertNull(response.getUser().getShopId());
    assertNull(response.getShop());
    assertNotNull(response.getAccessToken());
    verify(userAccountRepository).save(account);
    verifyNoInteractions(shopServiceAdapter);
  }
}
