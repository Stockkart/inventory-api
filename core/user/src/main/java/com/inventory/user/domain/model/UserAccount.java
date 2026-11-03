package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
// every authenticated request finds its user by token; created by loadtest/tools/ensure_user_token_index.py
@CompoundIndex(name = "tokens_accessToken", def = "{'tokens.accessToken': 1}")
public class UserAccount {

  /** Most sessions (devices) one user can have logged in at once; the oldest is dropped beyond this. */
  public static final int MAX_LIVE_TOKENS = 10;

  @Id
  private String userId;
  private String name;
  private UserRole role;
  private String shopId;
  private String email;
  /** 10-digit Indian mobile when set at signup. */
  private String phone;
  private String password;
  private boolean active;
  private boolean inviteAccepted;
  private Instant createdAt;
  private Instant updatedAt;
  private List<UserToken> tokens;
  /** Password reset token (random UUID); cleared after reset or expiry */
  private String passwordResetToken;
  /** Expiry time for password reset token */
  private Instant passwordResetTokenExpiresAt;

  public List<UserToken> getTokens() {
    if (tokens == null) {
      tokens = new ArrayList<>();
    }
    return tokens;
  }

  /**
   * Adds a newly issued token, first dropping this user's expired tokens and then, if more than
   * {@link #MAX_LIVE_TOKENS} would remain, the oldest live ones (those sessions are logged out).
   */
  public void addToken(UserToken token, Instant now) {
    List<UserToken> list = getTokens();
    list.removeIf(t -> t == null || (t.getExpiresAt() != null && !t.getExpiresAt().isAfter(now)));
    int excess = list.size() + 1 - MAX_LIVE_TOKENS;
    if (excess > 0) {
      List<UserToken> oldestFirst = new ArrayList<>(list);
      oldestFirst.sort(Comparator.comparing(
          UserToken::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
      java.util.Set<UserToken> drop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      drop.addAll(oldestFirst.subList(0, excess));
      list.removeIf(drop::contains);
    }
    list.add(token);
  }

  /**
   * Removes a token from this account by deviceId or accessToken.
   *
   * @param deviceId    Optional deviceId to match and remove
   * @param accessToken Optional accessToken to match and remove
   * @return The deviceId of the removed token, or null if no matching token was found
   */
  public String removeToken(String deviceId, String accessToken) {
    if (getTokens().isEmpty()) {
      return null;
    }

    // Remove by deviceId if provided
    if (deviceId != null && !deviceId.trim().isEmpty()) {
      boolean removed = getTokens().removeIf(token ->
          deviceId.equals(token.getDeviceId()));
      return removed ? deviceId : null;
    }

    // Remove by accessToken if provided
    if (accessToken != null && !accessToken.trim().isEmpty()) {
      Optional<UserToken> tokenToRemove = getTokens().stream()
          .filter(token -> accessToken.equals(token.getAccessToken()))
          .findFirst();
      if (tokenToRemove.isPresent()) {
        String removedDeviceId = tokenToRemove.get().getDeviceId();
        getTokens().remove(tokenToRemove.get());
        return removedDeviceId;
      }
    }

    return null;
  }
}

