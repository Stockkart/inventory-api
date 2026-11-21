package com.inventory.user.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A signed-in admin. Only the SHA-256 of the bearer token is stored. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = AdminSession.COLLECTION)
public class AdminSession {

  public static final String COLLECTION = "admin_sessions";

  @Id
  private String id;
  private String tokenHash;
  private String adminId;
  private Instant createdAt;
  /** A TTL index removes the row some time after this; readers must still check it. */
  private Instant expiresAt;

  public boolean isExpiredAt(Instant now) {
    return expiresAt == null || !expiresAt.isAfter(now);
  }
}
