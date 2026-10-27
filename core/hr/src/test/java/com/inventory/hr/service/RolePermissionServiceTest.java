package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.HrRolePermissionsRepository;
import com.inventory.hr.rest.dto.request.UpdateHrRolePermissionsRequest;
import com.inventory.hr.rest.dto.response.HrRolePermissionsResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.validation.RolePermissionValidator;
import com.inventory.user.domain.model.UserRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RolePermissionServiceTest {

  @Mock private HrRolePermissionsRepository repository;
  @Spy private RolePermissionValidator validator = new RolePermissionValidator();
  @Mock private HrPermissionGuard guard;
  @Mock private HrAuditService auditService;
  @InjectMocks private RolePermissionService service;

  private static UpdateHrRolePermissionsRequest request(Map<String, List<String>> roles) {
    UpdateHrRolePermissionsRequest r = new UpdateHrRolePermissionsRequest();
    r.setRoles(roles);
    return r;
  }

  @Test
  void anUnsavedGridShowsTheDefaultsAndTheDependencyRules() {
    when(repository.findByShopId(SHOP)).thenReturn(Optional.empty());

    HrRolePermissionsResponse response = service.get(USER, SHOP);

    assertEquals(List.of("SELF_CHECK_IN"), response.getRoles().get("CASHIER"));
    assertEquals(List.of("VIEW_EMPLOYEES", "VIEW_SALARIES"),
        response.getRequires().get("RUN_PAYROLL"));
    assertNull(response.getUpdatedAt());
  }

  @Test
  void onlyTheOwnerMayReadOrSave() {
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "no"))
        .when(guard).requireOwner(USER, SHOP);

    assertThrows(BaseException.class, () -> service.get(USER, SHOP));
    assertThrows(BaseException.class, () -> service.update(USER, SHOP,
        request(Map.of("CASHIER", List.of("VIEW_ATTENDANCE")))));
    verify(repository, never()).save(any());
  }

  @Test
  void savingClosesTheRowAndKeepsRolesNotSent() {
    when(repository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(repository.save(any(HrRolePermissions.class))).thenAnswer(i -> i.getArgument(0));

    HrRolePermissionsResponse response = service.update(USER, SHOP,
        request(Map.of("CASHIER", List.of("RUN_PAYROLL"))));

    assertEquals(List.of("VIEW_EMPLOYEES", "VIEW_SALARIES", "RUN_PAYROLL", "SELF_CHECK_IN"),
        response.getRoles().get("CASHIER"));
    // MANAGER was not sent: it keeps its (default) row
    assertEquals(List.of("VIEW_ATTENDANCE", "MARK_ATTENDANCE", "APPROVE_LEAVE", "VIEW_EMPLOYEES",
            "SELF_CHECK_IN"),
        response.getRoles().get("MANAGER"));
    ArgumentCaptor<HrRolePermissions> saved = ArgumentCaptor.forClass(HrRolePermissions.class);
    verify(repository).save(saved.capture());
    assertEquals(SHOP, saved.getValue().getShopId());
    // the request neither ticked nor listed SELF_CHECK_IN (an older client): it stays unknown,
    // so every role keeps its default for it
    assertFalse(saved.getValue().getKnownPermissions().contains(HrPermission.SELF_CHECK_IN));
    assertEquals(3, saved.getValue().getRoles().size());
  }

  @Test
  void aClientThatShowsSelfCheckInCanUntickItForGood() {
    when(repository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(repository.save(any(HrRolePermissions.class))).thenAnswer(i -> i.getArgument(0));
    UpdateHrRolePermissionsRequest r = request(Map.of("CASHIER", List.of()));
    r.setKnownPermissions(List.of("SELF_CHECK_IN"));

    HrRolePermissionsResponse response = service.update(USER, SHOP, r);

    assertEquals(List.of(), response.getRoles().get("CASHIER"));
    ArgumentCaptor<HrRolePermissions> saved = ArgumentCaptor.forClass(HrRolePermissions.class);
    verify(repository).save(saved.capture());
    assertTrue(saved.getValue().getKnownPermissions().contains(HrPermission.SELF_CHECK_IN));
    assertTrue(HrAccessService.rowFor(UserRole.CASHIER, saved.getValue()).isEmpty());
  }

  @Test
  void anOlderClientSavingWithoutSelfCheckInDoesNotTakeItAway() {
    HrRolePermissions old = new HrRolePermissions();
    old.setRoles(Map.of(UserRole.MANAGER, List.of(HrPermission.VIEW_ATTENDANCE)));
    when(repository.findByShopId(SHOP)).thenReturn(Optional.of(old));
    when(repository.save(any(HrRolePermissions.class))).thenAnswer(i -> i.getArgument(0));

    HrRolePermissionsResponse response = service.update(USER, SHOP,
        request(Map.of("MANAGER", List.of("VIEW_ATTENDANCE"), "ADMIN", List.of())));

    assertEquals(List.of("VIEW_ATTENDANCE", "SELF_CHECK_IN"), response.getRoles().get("MANAGER"));
    assertEquals(List.of("SELF_CHECK_IN"), response.getRoles().get("ADMIN"));
  }

  @Test
  void anUnknownNameInKnownPermissionsIsRejected() {
    when(repository.findByShopId(SHOP)).thenReturn(Optional.empty());
    UpdateHrRolePermissionsRequest r = request(Map.of("CASHIER", List.of()));
    r.setKnownPermissions(List.of("SELF_CHECKIN"));

    assertThrows(ValidationException.class, () -> service.update(USER, SHOP, r));
    verify(repository, never()).save(any());
  }

  @Test
  void aSaveIsAudited() {
    when(repository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(repository.save(any(HrRolePermissions.class))).thenAnswer(i -> i.getArgument(0));

    service.update(USER, SHOP, request(Map.of("ADMIN", List.of())));

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(entry.capture());
    assertEquals(HrAuditAction.ROLE_PERMISSIONS_CHANGED, entry.getValue().action());
    // ADMIN unticked everything the request knows; SELF_CHECK_IN was not sent, so it keeps
    // its default
    assertEquals(List.of("SELF_CHECK_IN"), entry.getValue().after().get("ADMIN"));
  }

  @Test
  void ownerAndUnknownNamesAreRejected() {
    assertThrows(ValidationException.class, () -> service.update(USER, SHOP,
        request(Map.of("OWNER", List.of("VIEW_ATTENDANCE")))));
    assertThrows(ValidationException.class, () -> service.update(USER, SHOP,
        request(Map.of("ADMIN", List.of("VIEW_EVERYTHING")))));
    assertThrows(ValidationException.class, () -> service.update(USER, SHOP, request(Map.of())));
  }

  @Test
  void aStoredRowIsShownClosed() {
    HrRolePermissions grid = new HrRolePermissions();
    grid.setRoles(Map.of(UserRole.MANAGER, List.of(HrPermission.ADD_EDIT_EMPLOYEES)));
    // saved knowing every permission, so only the closure adds anything
    grid.setKnownPermissions(List.of(HrPermission.values()));
    when(repository.findByShopId(SHOP)).thenReturn(Optional.of(grid));

    assertEquals(List.of("VIEW_EMPLOYEES", "ADD_EDIT_EMPLOYEES"),
        service.get(USER, SHOP).getRoles().get("MANAGER"));
  }
}
