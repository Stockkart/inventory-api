package com.inventory.common.audit;

/** What kind of account {@link AuditEntry#getActorUserId()} names. */
public enum AuditActorType {
  /** A shop-app user account ({@code users}); entries written before this field read as null = USER. */
  USER,
  /** A Stockkart platform-admin account ({@code admin_users}). */
  ADMIN,
  /** No person: start-up jobs and migrations. */
  SYSTEM
}
