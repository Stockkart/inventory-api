// Feature: hr-module, Property 1: Effective permissions follow owner → switch → grid → default
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.user.domain.model.UserRole;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 1: for any role, HR switch (true, false, absent) and grid, the result is every
 * permission for the owner, nothing when the switch is off, else the closed stored row for the role
 * or its R1.4 default; and every result is closed under the dependency rules.
 *
 * <p><b>Validates: Requirements 1.2, 1.3, 1.4, 1.9</b>
 */
class HrAccessServiceProperties {

  @Property(tries = 100)
  void resolutionFollowsOwnerSwitchGridDefault(
      @ForAll boolean owner,
      @ForAll("switches") Boolean hrModule,
      @ForAll("roles") UserRole role,
      @ForAll("grids") HrRolePermissions grid) {
    Set<HrPermission> result = HrAccessService.resolve(owner, hrModule, role, grid);

    Set<HrPermission> expected;
    if (owner) {
      expected = EnumSet.allOf(HrPermission.class);
    } else if (Boolean.FALSE.equals(hrModule)) {
      expected = EnumSet.noneOf(HrPermission.class);
    } else if (!HrAccessService.GRID_ROLES.contains(role)) {
      expected = EnumSet.noneOf(HrPermission.class);
    } else if (grid.getRoles().containsKey(role)) {
      expected = HrPermission.closure(grid.getRoles().get(role));
    } else {
      expected = EnumSet.noneOf(HrPermission.class);
      expected.addAll(HrAccessService.DEFAULT_ROWS.get(role));
    }
    assertEquals(expected, result);
    assertEquals(HrPermission.closure(result), result, "result is closed");
  }

  @Property(tries = 100)
  void withoutViewSalariesNoPayActionIsEverGranted(
      @ForAll boolean owner,
      @ForAll("switches") Boolean hrModule,
      @ForAll("roles") UserRole role,
      @ForAll("grids") HrRolePermissions grid) {
    Set<HrPermission> result = HrAccessService.resolve(owner, hrModule, role, grid);
    if (!result.contains(HrPermission.VIEW_SALARIES)) {
      assertTrue(!result.contains(HrPermission.RUN_PAYROLL)
          && !result.contains(HrPermission.RECORD_PAYMENTS)
          && !result.contains(HrPermission.MANAGE_ADVANCES));
    }
  }

  @Provide
  Arbitrary<Boolean> switches() {
    return Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3);
  }

  @Provide
  Arbitrary<UserRole> roles() {
    return Arbitraries.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER, UserRole.OWNER,
        UserRole.SUPER_USER);
  }

  @Provide
  Arbitrary<HrRolePermissions> grids() {
    Arbitrary<List<HrPermission>> row =
        Arbitraries.of(HrPermission.class).list().ofMaxSize(10).map(ArrayList::new);
    return Arbitraries.maps(Arbitraries.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER), row)
        .ofMaxSize(3)
        .map(roles -> {
          HrRolePermissions grid = new HrRolePermissions();
          grid.setRoles(new LinkedHashMap<>(roles));
          return grid;
        });
  }
}
