package com.inventory.user.api;

/** Platform-admin sessions ({@code /api/v1/admin/**}), for the request pipeline. */
public interface AdminSessions {

  /** Request attribute holding the signed-in admin's id; admin controllers use it as audit actor. */
  String ADMIN_ID_ATTRIBUTE = "adminId";

  /**
   * Resolves a bearer token to an active admin's live session.
   *
   * @throws com.inventory.common.exception.AuthenticationException when the token is unknown,
   *     expired, or its admin is inactive
   */
  AdminPrincipal authenticate(String token);

  /** The admin behind a request. */
  record AdminPrincipal(String adminId, boolean mustChangePassword) {}
}
