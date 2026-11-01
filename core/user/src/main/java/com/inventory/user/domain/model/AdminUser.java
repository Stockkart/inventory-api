package com.inventory.user.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A StockKart operator. Separate from shop users: no shop, no shop role, and its own sessions in
 * {@link AdminSession}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = AdminUser.COLLECTION)
public class AdminUser {

  public static final String COLLECTION = "admin_users";

  @Id
  private String id;
  /** Trimmed and lower-cased; unique. */
  private String email;
  private String name;
  private String passwordHash;
  private boolean active;
  /** Set for bootstrap and temporary passwords; cleared when the admin picks their own. */
  private boolean mustChangePassword;
  private int failedLoginCount;
  private Instant lockedUntil;
  /** Null for accounts created by the startup bootstrap. */
  private String createdByAdminId;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant lastLoginAt;

  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
