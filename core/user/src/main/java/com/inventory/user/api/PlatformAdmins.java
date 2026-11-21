package com.inventory.user.api;

/** Whether an account holds the Stockkart platform-admin role. */
public interface PlatformAdmins {

  /** True only when the user exists and holds {@code PLATFORM_ADMIN}. */
  boolean isPlatformAdmin(String userId);
}
