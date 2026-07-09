package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class UserAccount {

  @Id
  private String userId;
  private String name;
  private UserRole role;
  private String shopId;
  private String email;
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

