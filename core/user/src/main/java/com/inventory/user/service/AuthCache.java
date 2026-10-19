package com.inventory.user.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Short-lived memory of the per-request auth checks, so a busy screen does not look the user up
 * on every call:
 *
 * <ul>
 *   <li>token → the user it belongs to (a trimmed copy: no password, no other tokens), and
 *   <li>user + shop → whether the user may act in that shop.
 * </ul>
 *
 * <p>Entries live {@code auth.cache.ttl-seconds} (60 s) at most. Anything that changes a user or a
 * membership through the repositories evicts that user ({@link AuthCacheEvictionListener}), again
 * after the surrounding transaction commits, so a logout, deactivation, shop switch or role change
 * takes effect on the next request on this instance. Changes made outside this instance (another
 * instance, a script, the database directly) are picked up when the entry expires.
 *
 * <p>Before running more than one instance, add cross-instance eviction (Mongo change streams on
 * {@code users} and {@code user_shop_memberships}); see tasks.md group 9.
 */
@Slf4j
@Component
public class AuthCache {

  /** What a token resolved to; copied out per request. */
  record TokenEntry(UserAccount account) {}

  private final boolean enabled;
  private final Cache<String, TokenEntry> tokens;
  private final Cache<String, Boolean> shopAccess;

  public AuthCache(
      @Value("${auth.cache.enabled:true}") boolean enabled,
      @Value("${auth.cache.ttl-seconds:60}") long ttlSeconds,
      @Value("${auth.cache.max-entries:10000}") long maxEntries) {
    this.enabled = enabled && ttlSeconds > 0;
    Duration ttl = Duration.ofSeconds(Math.max(ttlSeconds, 1));
    this.tokens = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxEntries).build();
    this.shopAccess = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxEntries).build();
    log.info("Auth cache {} (ttl {} s, max {} entries each)", this.enabled ? "on" : "off", ttlSeconds, maxEntries);
  }

  // --- tokens ---

  /**
   * The cached user for this token, as a fresh copy holding only this token (with its expiry, so
   * the caller still rejects it on time); null when not cached.
   */
  UserAccount getAccount(String accessToken) {
    if (!enabled || accessToken == null) {
      return null;
    }
    TokenEntry e = tokens.getIfPresent(accessToken);
    return e != null ? copy(e.account()) : null;
  }

  void putAccount(String accessToken, UserAccount account, UserToken token) {
    if (!enabled || accessToken == null || account == null || token == null) {
      return;
    }
    tokens.put(accessToken, new TokenEntry(trimmed(account, token)));
  }

  // --- shop access ---

  Boolean getShopAccess(String userId, String shopId) {
    return enabled && userId != null && shopId != null ? shopAccess.getIfPresent(key(userId, shopId)) : null;
  }

  void putShopAccess(String userId, String shopId, boolean allowed) {
    if (enabled && userId != null && shopId != null) {
      shopAccess.put(key(userId, shopId), allowed);
    }
  }

  // --- eviction ---

  /** Forget everything about this user now, and again once the current transaction commits. */
  public void evictUser(String userId) {
    if (userId == null) {
      return;
    }
    evictUserNow(userId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // a request between this save and the commit could re-cache the old state
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          evictUserNow(userId);
        }
      });
    }
  }

  /** Forget all shop-access answers (used when a membership is deleted and its user is unknown). */
  public void evictAllShopAccess() {
    shopAccess.invalidateAll();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          shopAccess.invalidateAll();
        }
      });
    }
  }

  public void clear() {
    tokens.invalidateAll();
    shopAccess.invalidateAll();
  }

  private void evictUserNow(String userId) {
    tokens.asMap().values().removeIf(e -> userId.equals(e.account().getUserId()));
    String prefix = userId + '|';
    shopAccess.asMap().keySet().removeIf(k -> k.startsWith(prefix));
  }

  private static String key(String userId, String shopId) {
    return userId + '|' + shopId;
  }

  /** The account without secrets or other sessions; what a request needs to know about its caller. */
  private static UserAccount trimmed(UserAccount a, UserToken token) {
    UserAccount t = copy(a);
    t.setPassword(null);
    t.setPasswordResetToken(null);
    t.setPasswordResetTokenExpiresAt(null);
    t.setTokens(new ArrayList<>(List.of(copy(token))));
    return t;
  }

  private static UserAccount copy(UserAccount a) {
    UserAccount c = new UserAccount();
    c.setUserId(a.getUserId());
    c.setName(a.getName());
    c.setRole(a.getRole());
    c.setShopId(a.getShopId());
    c.setEmail(a.getEmail());
    c.setPhone(a.getPhone());
    c.setPassword(a.getPassword());
    c.setActive(a.isActive());
    c.setInviteAccepted(a.isInviteAccepted());
    c.setCreatedAt(a.getCreatedAt());
    c.setUpdatedAt(a.getUpdatedAt());
    List<UserToken> ts = new ArrayList<>();
    for (UserToken tok : a.getTokens()) {
      ts.add(copy(tok));
    }
    c.setTokens(ts);
    c.setPasswordResetToken(a.getPasswordResetToken());
    c.setPasswordResetTokenExpiresAt(a.getPasswordResetTokenExpiresAt());
    return c;
  }

  private static UserToken copy(UserToken t) {
    return new UserToken(t.getAccessToken(), t.getRefreshToken(), t.getDeviceId(), t.getCreatedAt(), t.getExpiresAt());
  }
}
