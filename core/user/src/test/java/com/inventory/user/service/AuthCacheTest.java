package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.model.UserToken;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The login check is remembered for a short time and forgotten as soon as the user or a
 * membership changes on this instance.
 */
class AuthCacheTest {

  private static final String TOKEN = "token-1";

  private UserAccountRepository users;
  private UserShopMembershipRepository memberships;
  private AuthCache cache;
  private TokenValidationService tokens;
  private UserShopMembershipService access;
  private AuthCacheEvictionListener listener;

  @BeforeEach
  void setUp() {
    users = mock(UserAccountRepository.class);
    memberships = mock(UserShopMembershipRepository.class);
    cache = new AuthCache(true, 60, 1000);
    tokens = new TokenValidationService();
    ReflectionTestUtils.setField(tokens, "userAccountRepository", users);
    ReflectionTestUtils.setField(tokens, "authCache", cache);
    access = new UserShopMembershipService();
    ReflectionTestUtils.setField(access, "membershipRepository", memberships);
    ReflectionTestUtils.setField(access, "userAccountRepository", users);
    ReflectionTestUtils.setField(access, "authCache", cache);
    listener = new AuthCacheEvictionListener(cache);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private static UserAccount account(String userId, Instant tokenExpires) {
    UserAccount a = new UserAccount();
    a.setUserId(userId);
    a.setRole(UserRole.OWNER);
    a.setShopId("shop-1");
    a.setActive(true);
    a.setPassword("hash");
    a.setPasswordResetToken("reset");
    a.setTokens(new ArrayList<>(List.of(
        new UserToken(TOKEN, "r1", "d1", Instant.now(), tokenExpires),
        new UserToken("other-token", "r2", "d2", Instant.now(), tokenExpires))));
    return a;
  }

  @Test
  void secondRequestWithTheSameTokenDoesNotLookTheUserUp() {
    when(users.findByAccessToken(TOKEN)).thenReturn(Optional.of(account("u1", Instant.now().plus(Duration.ofDays(1)))));

    tokens.validateToken(TOKEN);
    UserAccount second = tokens.validateToken(TOKEN);

    verify(users, times(1)).findByAccessToken(TOKEN);
    assertThat(second.getUserId()).isEqualTo("u1");
    assertThat(second.getShopId()).isEqualTo("shop-1");
    // what is remembered carries no secrets and no other sessions
    assertThat(second.getPassword()).isNull();
    assertThat(second.getPasswordResetToken()).isNull();
    assertThat(second.getTokens()).extracting(UserToken::getAccessToken).containsExactly(TOKEN);
  }

  @Test
  void callerCannotChangeWhatIsRemembered() {
    when(users.findByAccessToken(TOKEN)).thenReturn(Optional.of(account("u1", Instant.now().plus(Duration.ofDays(1)))));
    tokens.validateToken(TOKEN);

    tokens.validateToken(TOKEN).setShopId("tampered");

    assertThat(tokens.validateToken(TOKEN).getShopId()).isEqualTo("shop-1");
  }

  @Test
  void tokenThatExpiresWhileRememberedIsRefusedOnTime() {
    UserAccount a = account("u1", Instant.now().minusSeconds(1));
    cache.putAccount(TOKEN, a, a.getTokens().get(0));

    assertThatThrownBy(() -> tokens.validateToken(TOKEN))
        .isInstanceOf(AuthenticationException.class)
        .hasMessageContaining("expired");
    verify(users, never()).findByAccessToken(anyString());
  }

  @Test
  void unknownTokenIsNotRemembered() {
    when(users.findByAccessToken("nope")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tokens.validateToken("nope")).isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> tokens.validateToken("nope")).isInstanceOf(AuthenticationException.class);
    verify(users, times(2)).findByAccessToken("nope");
  }

  @Test
  void savingTheUserForgetsItSoLogoutAndDeactivationApplyAtOnce() {
    UserAccount stored = account("u1", Instant.now().plus(Duration.ofDays(1)));
    when(users.findByAccessToken(TOKEN)).thenReturn(Optional.of(stored));
    tokens.validateToken(TOKEN);

    // logout (or deactivation) saves the account
    UserAccount loggedOut = account("u1", Instant.now().plus(Duration.ofDays(1)));
    loggedOut.getTokens().removeIf(t -> TOKEN.equals(t.getAccessToken()));
    listener.onApplicationEvent(new AfterSaveEvent<>(loggedOut, new Document(), "users"));
    when(users.findByAccessToken(TOKEN)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tokens.validateToken(TOKEN)).isInstanceOf(AuthenticationException.class);
    verify(users, times(2)).findByAccessToken(TOKEN);
  }

  @Test
  void evictionIsRepeatedAfterTheTransactionSoAStaleReadCannotStick() {
    TransactionSynchronizationManager.initSynchronization();
    UserAccount a = account("u1", Instant.now().plus(Duration.ofDays(1)));

    cache.evictUser("u1");
    // a concurrent request reads the old state before the commit and remembers it
    cache.putAccount(TOKEN, a, a.getTokens().get(0));
    assertThat(cache.getAccount(TOKEN)).isNotNull();

    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }
    assertThat(cache.getAccount(TOKEN)).isNull();
  }

  @Test
  void shopAccessIsRememberedAndForgottenOnMembershipChange() {
    when(memberships.existsByUserIdAndShopIdAndActiveTrue("u1", "shop-2")).thenReturn(true);

    assertThat(access.hasAccess("u1", "shop-2")).isTrue();
    assertThat(access.hasAccess("u1", "shop-2")).isTrue();
    verify(memberships, times(1)).existsByUserIdAndShopIdAndActiveTrue("u1", "shop-2");

    UserShopMembership m = new UserShopMembership();
    m.setUserId("u1");
    m.setShopId("shop-2");
    listener.onApplicationEvent(new AfterSaveEvent<>(m, new Document(), "user_shop_memberships"));
    when(memberships.existsByUserIdAndShopIdAndActiveTrue("u1", "shop-2")).thenReturn(false);
    when(users.findById("u1")).thenReturn(Optional.empty());

    assertThat(access.hasAccess("u1", "shop-2")).isFalse();
  }

  @Test
  void deletedMembershipForgetsAllShopAccess() {
    when(memberships.existsByUserIdAndShopIdAndActiveTrue("u1", "shop-2")).thenReturn(true);
    access.hasAccess("u1", "shop-2");

    listener.onApplicationEvent(new AfterDeleteEvent<>(
        new Document("_id", "m-1"), UserShopMembership.class, "user_shop_memberships"));
    access.hasAccess("u1", "shop-2");

    verify(memberships, times(2)).existsByUserIdAndShopIdAndActiveTrue("u1", "shop-2");
  }

  @Test
  void otherUsersStayRemembered() {
    UserAccount a = account("u1", Instant.now().plus(Duration.ofDays(1)));
    UserAccount b = account("u2", Instant.now().plus(Duration.ofDays(1)));
    cache.putAccount("t-a", a, a.getTokens().get(0));
    cache.putAccount("t-b", b, b.getTokens().get(0));
    cache.putShopAccess("u2", "shop-9", true);

    cache.evictUser("u1");

    assertThat(cache.getAccount("t-a")).isNull();
    assertThat(cache.getAccount("t-b")).isNotNull();
    assertThat(cache.getShopAccess("u2", "shop-9")).isTrue();
  }

  @Test
  void disabledCacheRemembersNothing() {
    AuthCache off = new AuthCache(false, 60, 1000);
    UserAccount a = account("u1", Instant.now().plus(Duration.ofDays(1)));
    off.putAccount(TOKEN, a, a.getTokens().get(0));
    off.putShopAccess("u1", "shop-1", true);
    assertThat(off.getAccount(TOKEN)).isNull();
    assertThat(off.getShopAccess("u1", "shop-1")).isNull();
  }
}
