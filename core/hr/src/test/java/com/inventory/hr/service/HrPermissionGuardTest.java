package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.enums.HrPermission;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HrPermissionGuardTest {

  @Mock private HrAccessService hrAccessService;
  @InjectMocks private HrPermissionGuard guard;

  @Test
  void aHeldPermissionPassesAndReturnsTheEffectiveSet() {
    EnumSet<HrPermission> held = EnumSet.of(HrPermission.VIEW_EMPLOYEES);
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(held);

    assertEquals(held, guard.require(USER, SHOP, HrPermission.VIEW_EMPLOYEES));
  }

  @Test
  void aMissingPermissionIs403AccessDenied() {
    when(hrAccessService.permissions(USER, SHOP))
        .thenReturn(EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES));

    BaseException e = assertThrows(BaseException.class, () -> guard.require(USER, SHOP,
        HrPermission.ADD_EDIT_EMPLOYEES, HrPermission.VIEW_SALARIES));
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
  }

  @Test
  void noHrPermissionAtAllFailsRequireAny() {
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(EnumSet.noneOf(HrPermission.class));

    assertEquals(ErrorCode.ACCESS_DENIED,
        assertThrows(BaseException.class, () -> guard.requireAny(USER, SHOP)).getErrorCode());
  }

  @Test
  void onlyTheOwnerPassesRequireOwner() {
    when(hrAccessService.isOwner(USER, SHOP)).thenReturn(false);

    assertEquals(ErrorCode.ACCESS_DENIED,
        assertThrows(BaseException.class, () -> guard.requireOwner(USER, SHOP)).getErrorCode());
  }

  @Test
  void aMissingShopOrUserIsUnauthorised() {
    assertThrows(AuthenticationException.class,
        () -> guard.require(USER, " ", HrPermission.VIEW_EMPLOYEES));
    assertThrows(AuthenticationException.class,
        () -> guard.require(null, SHOP, HrPermission.VIEW_EMPLOYEES));
  }
}
