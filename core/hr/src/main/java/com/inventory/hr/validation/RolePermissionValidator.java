package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.rest.dto.request.UpdateHrRolePermissionsRequest;
import com.inventory.hr.service.HrAccessService;
import com.inventory.user.domain.model.UserRole;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RolePermissionValidator {

  /**
   * Checks role keys and permission names and returns them parsed. Unknown names are rejected
   * (not ignored), so a typo cannot silently drop a permission.
   */
  public Map<UserRole, Set<HrPermission>> validateAndParse(UpdateHrRolePermissionsRequest request) {
    if (request == null || request.getRoles() == null || request.getRoles().isEmpty()) {
      throw new ValidationException("Send at least one role row");
    }
    Map<UserRole, Set<HrPermission>> parsed = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> row : request.getRoles().entrySet()) {
      UserRole role = parseRole(row.getKey());
      if (row.getValue() == null) {
        throw new ValidationException("Permissions for " + role.name() + " are required");
      }
      Set<HrPermission> permissions = EnumSet.noneOf(HrPermission.class);
      for (String name : row.getValue()) {
        permissions.add(parsePermission(name));
      }
      parsed.put(role, permissions);
    }
    return parsed;
  }

  /** {@code knownPermissions} parsed (unknown names rejected); empty when not sent. */
  public Set<HrPermission> parseKnown(UpdateHrRolePermissionsRequest request) {
    Set<HrPermission> known = EnumSet.noneOf(HrPermission.class);
    if (request != null && request.getKnownPermissions() != null) {
      for (String name : request.getKnownPermissions()) {
        known.add(parsePermission(name));
      }
    }
    return known;
  }

  private static UserRole parseRole(String key) {
    for (UserRole role : HrAccessService.GRID_ROLES) {
      if (role.name().equals(key)) {
        return role;
      }
    }
    throw new ValidationException("Role must be one of ADMIN, MANAGER, CASHIER");
  }

  private static HrPermission parsePermission(String name) {
    for (HrPermission p : HrPermission.values()) {
      if (p.name().equals(name)) {
        return p;
      }
    }
    throw new ValidationException("Unknown HR permission: " + name);
  }
}
