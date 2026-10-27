// Feature: hr-module, Property 1: Effective permissions follow owner → switch → grid → default, and every saved row is closed under the dependency rules
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.HrRolePermissionsRepository;
import com.inventory.hr.rest.dto.request.UpdateHrRolePermissionsRequest;
import com.inventory.hr.validation.RolePermissionValidator;
import com.inventory.user.domain.model.UserRole;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Saved grids are closed under the dependency rules (design Property 2, tasks 2.4): for any
 * ticked set the stored row contains every permission each ticked one requires, nothing the
 * owner did not tick or that is not required, and saving the result again stores the same rows.
 *
 * <p><b>Validates: Requirements 1.3, 4.13</b>
 */
class RolePermissionServiceProperties {

  @Property(tries = 100)
  void closureAddsExactlyTheRequiredPermissionsAndIsIdempotent(
      @ForAll("ticks") Set<HrPermission> ticked) {
    Set<HrPermission> closed = HrPermission.closure(ticked);

    assertTrue(closed.containsAll(ticked));
    for (HrPermission p : closed) {
      assertTrue(closed.containsAll(p.requires()), p + " is missing what it requires");
      boolean tickedOrRequired = ticked.contains(p)
          || ticked.stream().anyMatch(t -> t.requires().contains(p));
      assertTrue(tickedOrRequired, p + " was added without being needed");
    }
    assertEquals(closed, HrPermission.closure(closed));
  }

  @Property(tries = 100)
  void savingStoresClosedRowsAndSavingTwiceChangesNothing(
      @ForAll("rows") Map<UserRole, Set<HrPermission>> rows) {
    AtomicReference<HrRolePermissions> store = new AtomicReference<>();
    RolePermissionService service = service(store);

    service.update(USER, SHOP, request(rows));
    Map<UserRole, List<HrPermission>> first = new LinkedHashMap<>(store.get().getRoles());
    // the request decides the original ten plus anything ticked; the rest keeps its default
    Set<HrPermission> known = EnumSet.copyOf(HrAccessService.LEGACY_KNOWN);
    rows.values().forEach(known::addAll);

    for (UserRole role : HrAccessService.GRID_ROLES) {
      Set<HrPermission> saved = EnumSet.noneOf(HrPermission.class);
      saved.addAll(first.get(role));
      assertEquals(HrPermission.closure(saved), saved, role + " row is not closed");
      if (rows.containsKey(role)) {
        assertEquals(HrAccessService.withUnknownDefaults(role, rows.get(role), known), saved);
      }
    }

    Map<UserRole, Set<HrPermission>> again = new LinkedHashMap<>();
    first.forEach((role, list) -> {
      Set<HrPermission> set = EnumSet.noneOf(HrPermission.class);
      set.addAll(list);
      again.put(role, set);
    });
    service.update(USER, SHOP, request(again));
    assertEquals(first, store.get().getRoles());
  }

  private static RolePermissionService service(AtomicReference<HrRolePermissions> store) {
    HrRolePermissionsRepository repository = mock(HrRolePermissionsRepository.class);
    when(repository.findByShopId(SHOP)).thenAnswer(i -> Optional.ofNullable(store.get()));
    when(repository.save(any(HrRolePermissions.class))).thenAnswer(i -> {
      store.set(i.getArgument(0));
      return i.getArgument(0);
    });
    RolePermissionService service = new RolePermissionService();
    ReflectionTestUtils.setField(service, "repository", repository);
    ReflectionTestUtils.setField(service, "validator", new RolePermissionValidator());
    ReflectionTestUtils.setField(service, "guard", mock(HrPermissionGuard.class));
    ReflectionTestUtils.setField(service, "auditService", mock(HrAuditService.class));
    return service;
  }

  private static UpdateHrRolePermissionsRequest request(Map<UserRole, Set<HrPermission>> rows) {
    UpdateHrRolePermissionsRequest request = new UpdateHrRolePermissionsRequest();
    Map<String, List<String>> roles = new LinkedHashMap<>();
    rows.forEach((role, set) -> roles.put(role.name(),
        set.stream().map(HrPermission::name).toList()));
    request.setRoles(roles);
    return request;
  }

  @Provide
  Arbitrary<Set<HrPermission>> ticks() {
    return Arbitraries.of(HrPermission.class).set().ofMaxSize(10);
  }

  @Provide
  Arbitrary<Map<UserRole, Set<HrPermission>>> rows() {
    return Arbitraries.maps(
            Arbitraries.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER), ticks())
        .ofMinSize(1).ofMaxSize(3);
  }
}
