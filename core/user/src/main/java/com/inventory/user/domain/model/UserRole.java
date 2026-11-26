package com.inventory.user.domain.model;

public enum UserRole {
  OWNER,
  ADMIN,
  MANAGER,
  CASHIER,
  /**
   * A worker who uses the Stockkart Team app. Created only by signing up from that app;
   * a WORKER membership in a shop is created only by approving the worker's join or link request
   * (never from the Users page, an invitation or a staff join request). Grants no shop module and
   * no HR permission: a WORKER reaches only the self-service paths for their own employee record.
   */
  WORKER,
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

  /** True for {@link #WORKER}: a shop member with self-service access only, never staff. */
  public boolean isWorkerRole() {
    return this == WORKER;
  }
}
