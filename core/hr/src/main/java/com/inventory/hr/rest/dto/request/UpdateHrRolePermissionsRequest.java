package com.inventory.hr.rest.dto.request;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * New grid rows: role (ADMIN, MANAGER, CASHIER) to HrPermission names. A role left out keeps its
 * current row. Missing dependencies are added on save (closure), never rejected.
 */
@Data
public class UpdateHrRolePermissionsRequest {
  private Map<String, List<String>> roles;
  /**
   * Every permission the client's grid shows (so an unticked one is a decision, not "unknown");
   * null = only the permissions named in {@code roles}. A permission neither listed here nor
   * ticked for any role keeps its role default, so an older client that does not know
   * SELF_CHECK_IN never takes it away by saving.
   */
  private List<String> knownPermissions;
}
