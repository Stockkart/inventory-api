package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.access;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrRolePermissionsRepository;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.service.RbacService;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class HrAccessServiceTest {

  @Mock private RbacService rbacService;
  @Mock private HrRolePermissionsRepository rolePermissionsRepository;
  @Mock private EmployeeRepository employeeRepository;
  @InjectMocks private HrAccessService service;

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void theOwnerHasEveryPermissionWithoutAGridRead() {
    assertEquals(EnumSet.allOf(HrPermission.class),
        service.permissionsFor(access(true, "OWNER", true), SHOP));
    verifyNoInteractions(rolePermissionsRepository);
  }

  @Test
  void aSwitchedOffMemberHasNoneWithoutAGridRead() {
    assertTrue(service.permissionsFor(access(false, "ADMIN", false), SHOP).isEmpty());
    verifyNoInteractions(rolePermissionsRepository);
  }

  @Test
  void aStoredRowWinsOverTheDefault() {
    HrRolePermissions grid = new HrRolePermissions();
    grid.setRoles(Map.of(UserRole.CASHIER, List.of(HrPermission.MARK_ATTENDANCE)));
    when(rolePermissionsRepository.findByShopId(SHOP)).thenReturn(Optional.of(grid));

    // stored rows are closed again on read: MARK_ATTENDANCE brings VIEW_ATTENDANCE
    assertEquals(EnumSet.of(HrPermission.MARK_ATTENDANCE, HrPermission.VIEW_ATTENDANCE),
        service.permissionsFor(access(false, "CASHIER", true), SHOP));
  }

  @Test
  void withoutAStoredRowTheR14DefaultsApply() {
    when(rolePermissionsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());

    assertEquals(EnumSet.complementOf(EnumSet.of(HrPermission.MANAGE_SETTINGS)),
        service.permissionsFor(access(false, "ADMIN", null), SHOP));
    assertEquals(EnumSet.of(HrPermission.VIEW_ATTENDANCE, HrPermission.MARK_ATTENDANCE,
            HrPermission.APPROVE_LEAVE, HrPermission.VIEW_EMPLOYEES),
        service.permissionsFor(access(false, "MANAGER", true), SHOP));
    assertTrue(service.permissionsFor(access(false, "CASHIER", true), SHOP).isEmpty());
  }

  @Test
  void anOwnerRoleWithoutTheOwnerRelationshipGetsNothing() {
    when(rolePermissionsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());

    assertTrue(service.permissionsFor(access(false, "OWNER", true), SHOP).isEmpty());
  }

  @Test
  void theInterceptorsAccessIsReusedForTheSameRequest() {
    ShopAccessResponse cached = access(true, "OWNER", true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("userId", USER);
    request.setAttribute("shopId", SHOP);
    request.setAttribute(RbacService.ACCESS_ATTRIBUTE, cached);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    assertSame(cached, service.shopAccess(USER, SHOP));
    verify(rbacService, never()).getEffectiveAccess(anyString(), anyString());
  }

  @Test
  void accessForAnotherShopIsResolvedAfresh() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("userId", USER);
    request.setAttribute("shopId", "other-shop");
    request.setAttribute(RbacService.ACCESS_ATTRIBUTE, access(true, "OWNER", true));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    ShopAccessResponse fresh = access(false, "CASHIER", true);
    when(rbacService.getEffectiveAccess(USER, SHOP)).thenReturn(fresh);

    assertSame(fresh, service.shopAccess(USER, SHOP));
  }

  @Test
  void withoutARequestTheAccessIsResolved() {
    ShopAccessResponse fresh = access(false, "MANAGER", true);
    when(rbacService.getEffectiveAccess(USER, SHOP)).thenReturn(fresh);

    assertSame(fresh, service.shopAccess(USER, SHOP));
  }

  @Test
  void theLinkedEmployeeIsFoundByShopAndCaller() {
    when(employeeRepository.findByShopIdAndUserId(SHOP, USER)).thenReturn(Optional.empty());

    assertNull(service.linkedEmployeeId(USER, SHOP));
  }
}
