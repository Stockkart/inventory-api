package com.inventory.user.service;

import com.inventory.user.domain.model.AdminSession;
import com.inventory.user.domain.model.AdminUser;

/** The admin behind a request and the session they used. */
public record AdminAuthentication(AdminUser admin, AdminSession session) {

  /** Request attribute holding the admin's id; admin controllers use it as the audit actor. */
  public static final String ADMIN_ID_ATTRIBUTE = com.inventory.user.api.AdminSessions.ADMIN_ID_ATTRIBUTE;

  public String adminId() {
    return admin.getId();
  }
}
