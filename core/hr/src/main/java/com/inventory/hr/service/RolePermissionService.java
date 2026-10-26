package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.HrRolePermissionsRepository;
import com.inventory.hr.rest.dto.request.UpdateHrRolePermissionsRequest;
import com.inventory.hr.rest.dto.response.HrRolePermissionsResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.validation.RolePermissionValidator;
import com.inventory.user.domain.model.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** The shop's HR role grid (R1.3). Owner only, both to read and to save. */
@Slf4j
@Service
public class RolePermissionService {

  @Autowired private HrRolePermissionsRepository repository;

  @Autowired private RolePermissionValidator validator;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrAuditService auditService;

  public HrRolePermissionsResponse get(String userId, String shopId) {
    guard.requireOwner(userId, shopId);
    return toResponse(repository.findByShopId(shopId).orElse(null));
  }

  /**
   * Saves the given rows closed under {@link HrPermission#requires()}: ticking RUN_PAYROLL also
   * stores VIEW_SALARIES and VIEW_EMPLOYEES, so a role can never act on pay it cannot see. Rows
   * not sent keep their current value. Saving the same grid twice stores the same rows.
   */
  public HrRolePermissionsResponse update(
      String userId, String shopId, UpdateHrRolePermissionsRequest request) {
    guard.requireOwner(userId, shopId);
    Map<UserRole, Set<HrPermission>> rows = validator.validateAndParse(request);
    HrRolePermissions grid = repository.findByShopId(shopId).orElseGet(() -> {
      HrRolePermissions fresh = new HrRolePermissions();
      fresh.setShopId(shopId);
      fresh.setCreatedAt(Instant.now());
      return fresh;
    });
    Map<String, Object> before = rowsSnapshot(grid);
    Map<UserRole, List<HrPermission>> stored = new LinkedHashMap<>();
    for (UserRole role : HrAccessService.GRID_ROLES) {
      Set<HrPermission> row = rows.containsKey(role)
          ? HrPermission.closure(rows.get(role))
          : HrAccessService.rowFor(role, grid);
      stored.put(role, new ArrayList<>(row));
    }
    grid.setRoles(stored);
    grid.setUpdatedAt(Instant.now());
    grid.setUpdatedByUserId(userId);
    HrRolePermissions saved;
    try {
      saved = repository.save(grid);
    } catch (DuplicateKeyException e) {
      // two first saves raced on the unique shopId index
      throw new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
          ErrorCode.HR_CONCURRENT_UPDATE.getMessage());
    } catch (DataAccessException e) {
      log.error("Database error while saving HR role grid for shop {}: {}", shopId, e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving HR permissions");
    }
    auditService.record(AuditEntry.of(shopId, userId, HrAuditAction.ROLE_PERMISSIONS_CHANGED,
        "HR_ROLE_PERMISSIONS", saved.getId(), before, rowsSnapshot(saved)));
    return toResponse(saved);
  }

  static HrRolePermissionsResponse toResponse(HrRolePermissions grid) {
    Map<String, List<String>> roles = new LinkedHashMap<>();
    for (UserRole role : HrAccessService.GRID_ROLES) {
      roles.put(role.name(), names(HrAccessService.rowFor(role, grid)));
    }
    Map<String, List<String>> requires = new LinkedHashMap<>();
    for (HrPermission p : HrPermission.values()) {
      if (!p.requires().isEmpty()) {
        requires.put(p.name(), names(p.requires()));
      }
    }
    return new HrRolePermissionsResponse(roles, requires, grid != null ? grid.getUpdatedAt() : null);
  }

  private static Map<String, Object> rowsSnapshot(HrRolePermissions grid) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (UserRole role : HrAccessService.GRID_ROLES) {
      snapshot.put(role.name(), names(HrAccessService.rowFor(role, grid)));
    }
    return snapshot;
  }

  private static List<String> names(java.util.Collection<HrPermission> permissions) {
    return permissions.stream().sorted().map(HrPermission::name).toList();
  }
}
