package com.inventory.user.domain.model;

public enum UserRole {
  OWNER,
  ADMIN,
  MANAGER,
  CASHIER,
  /**
   * Platform-level role for internal Stockkart staff (Stockkart HQ sign-in, platform admin
   * actions such as shop approval). Not a shop role: a SUPER_USER account has no shop and no shop
   * memberships, and no public API can grant it. Only the operator script
   * {@code scripts/super-user.mongodb.js} sets or clears it.
   */
  SUPER_USER;

  /** True for {@link #SUPER_USER}, the only role that is not tied to a shop. */
  public boolean isPlatformRole() {
    return this == SUPER_USER;
  }
}

