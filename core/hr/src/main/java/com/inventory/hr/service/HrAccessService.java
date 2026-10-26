package com.inventory.hr.service;

import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrRolePermissionsRepository;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.service.RbacService;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Effective HR permissions of a member (design "Permissions"): the owner has all ten; a member
 * whose HR switch ({@code modules.hr}) is off has none (self-service only); everyone else gets the
 * stored grid row for their role, or the R1.4 default row when the shop never saved one.
 */
@Slf4j
@Service
public class HrAccessService {

  /** R1.4 default rows; a role missing here (e.g. a non-shop role) gets nothing. */
  static final Map<UserRole, Set<HrPermission>> DEFAULT_ROWS =
      Map.of(
          UserRole.ADMIN, Collections.unmodifiableSet(
              EnumSet.complementOf(EnumSet.of(HrPermission.MANAGE_SETTINGS))),
          UserRole.MANAGER, Collections.unmodifiableSet(
              EnumSet.of(
                  HrPermission.VIEW_ATTENDANCE,
                  HrPermission.MARK_ATTENDANCE,
                  HrPermission.APPROVE_LEAVE,
                  HrPermission.VIEW_EMPLOYEES)),
          UserRole.CASHIER, Collections.unmodifiableSet(EnumSet.noneOf(HrPermission.class)));

  /** Roles that have a row in the grid; OWNER is never stored. */
  public static final List<UserRole> GRID_ROLES =
      List.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER);

  // @Lazy breaks the bean cycle RbacService -> HrAccessAdapterImpl -> HrAccessService ->
  // RbacService; RbacService is only needed when the interceptor did not leave the access behind.
  @Lazy @Autowired private RbacService rbacService;

  @Autowired private HrRolePermissionsRepository rolePermissionsRepository;

  @Autowired private EmployeeRepository employeeRepository;

  /**
   * Shop access of the caller. Reuses what {@code RbacModuleInterceptor} resolved for this request
   * ({@link RbacService#ACCESS_ATTRIBUTE}) when it belongs to the same user and shop; otherwise
   * (e.g. {@code /hr/me/**}, which skips the module check) resolves it once.
   */
  public ShopAccessResponse shopAccess(String userId, String shopId) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      Object cached = attributes.getAttribute(RbacService.ACCESS_ATTRIBUTE,
          RequestAttributes.SCOPE_REQUEST);
      if (cached instanceof ShopAccessResponse access
          && userId.equals(attributes.getAttribute("userId", RequestAttributes.SCOPE_REQUEST))
          && shopId.equals(attributes.getAttribute("shopId", RequestAttributes.SCOPE_REQUEST))) {
        return access;
      }
    }
    return rbacService.getEffectiveAccess(userId, shopId);
  }

  /** Effective HR permissions of the caller in the shop. */
  public Set<HrPermission> permissions(String userId, String shopId) {
    return permissionsFor(shopAccess(userId, shopId), shopId);
  }

  /** Effective HR permissions for already resolved access (one grid read unless owner/off). */
  public Set<HrPermission> permissionsFor(ShopAccessResponse access, String shopId) {
    if (access == null) {
      return EnumSet.noneOf(HrPermission.class);
    }
    Boolean hrModule = access.getModules() != null
        ? access.getModules().get(RbacService.MODULE_HR) : null;
    if (access.isOwner() || Boolean.FALSE.equals(hrModule)) {
      // owner or switched off: no need to read the grid
      return resolve(access.isOwner(), hrModule, parseRole(access.getRole()), null);
    }
    HrRolePermissions grid = rolePermissionsRepository.findByShopId(shopId).orElse(null);
    return resolve(false, hrModule, parseRole(access.getRole()), grid);
  }

  public boolean isOwner(String userId, String shopId) {
    return shopAccess(userId, shopId).isOwner();
  }

  /** The caller's own employee id in the shop; null = not linked. */
  public String linkedEmployeeId(String userId, String shopId) {
    return employeeRepository.findByShopIdAndUserId(shopId, userId).map(e -> e.getId()).orElse(null);
  }

  /**
   * The resolution order, free of I/O (property-tested). {@code hrModule} null counts as on.
   * A stored row is closed again on read, so a hand-edited document cannot grant RUN_PAYROLL
   * without VIEW_SALARIES.
   */
  public static Set<HrPermission> resolve(
      boolean owner, Boolean hrModule, UserRole role, HrRolePermissions grid) {
    if (owner) {
      return EnumSet.allOf(HrPermission.class);
    }
    if (Boolean.FALSE.equals(hrModule) || role == null) {
      return EnumSet.noneOf(HrPermission.class);
    }
    return rowFor(role, grid);
  }

  /** Stored row for the role (closed), else its R1.4 default; empty for roles not in the grid. */
  public static Set<HrPermission> rowFor(UserRole role, HrRolePermissions grid) {
    if (!GRID_ROLES.contains(role)) {
      // OWNER role without the owner relationship, or a platform role: fail closed
      return EnumSet.noneOf(HrPermission.class);
    }
    if (grid != null && grid.getRoles() != null && grid.getRoles().get(role) != null) {
      return HrPermission.closure(grid.getRoles().get(role));
    }
    EnumSet<HrPermission> defaults = EnumSet.noneOf(HrPermission.class);
    defaults.addAll(DEFAULT_ROWS.get(role));
    return defaults;
  }

  private static UserRole parseRole(String role) {
    if (role == null) {
      return null;
    }
    try {
      return UserRole.valueOf(role);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
