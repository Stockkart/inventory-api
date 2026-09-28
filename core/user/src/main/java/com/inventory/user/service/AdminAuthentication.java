package com.inventory.user.service;

import com.inventory.user.domain.model.AdminSession;
import com.inventory.user.domain.model.AdminUser;

/** The admin behind a request and the session they used. */
public record AdminAuthentication(AdminUser admin, AdminSession session) {

  public static final String REQUEST_ATTRIBUTE = "adminAuthentication";
  /** Request attribute holding the admin's id; admin controllers use it as the audit actor. */
  public static final String ADMIN_ID_ATTRIBUTE = "adminId";

  public String adminId() {
    return admin.getId();
  }
}
