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
}
