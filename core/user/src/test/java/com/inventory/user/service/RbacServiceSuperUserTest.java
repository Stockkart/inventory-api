package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.ShopRbacPolicyRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.validation.UserValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Shop RBAC fails closed (deny, no NPE) for SUPER_USER accounts and the SUPER_USER role. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceSuperUserTest {

  @Mock private ShopRbacPolicyRepository policyRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private MetricsWrapper metrics;
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private RbacService rbacService;

  private UserAccount account(String userId, UserRole role, String shopId) {
    UserAccount a = new UserAccount();
    a.setUserId(userId);
    a.setRole(role);
    a.setShopId(shopId);
    a.setActive(true);
    return a;
  }

  @Test
  void aSuperUserWithNoShopIsDeniedWithoutCrashing() {
    when(membershipService.hasAccess("staff-1", null)).thenReturn(false);
    when(userAccountRepository.findById("staff-1"))
        .thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER, null)));

    assertThrows(ValidationException.class,
        () -> rbacService.requireModule("staff-1", null, RbacService.MODULE_ACCOUNTING));
    assertThrows(ValidationException.class,
        () -> rbacService.requireTeamAccess("staff-1", null, RbacService.TEAM_MANAGE_SHOP_USERS));
  }

  @Test
  void aSuperUserIsDeniedEvenWithAStrayLegacyShopId() {
    // hasAccess is true through the legacy UserAccount.shopId fallback
    when(membershipService.hasAccess("staff-1", "shop-1")).thenReturn(true);
    when(userAccountRepository.findById("staff-1"))
        .thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER, "shop-1")));

    BaseException e = assertThrows(BaseException.class,
        () -> rbacService.getEffectiveAccess("staff-1", "shop-1"));
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
  }

  @Test
  void aStraySuperUserMembershipRoleGetsNoTeamOrModuleAccess() {
    UserShopMembership m = new UserShopMembership();
    m.setUserId("u-1");
    m.setShopId("shop-1");
    m.setRole(UserRole.SUPER_USER);
    m.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
    m.setActive(true);
    when(membershipService.hasAccess("u-1", "shop-1")).thenReturn(true);
    when(userAccountRepository.findById("u-1"))
        .thenReturn(Optional.of(account("u-1", UserRole.CASHIER, "shop-1")));
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("u-1", "shop-1")).thenReturn(Optional.of(m));
    when(policyRepository.findByShopId("shop-1")).thenReturn(Optional.empty());

    ShopAccessResponse access = rbacService.getEffectiveAccess("u-1", "shop-1");

    assertFalse(access.getTeam().isManageInvitations());
    assertFalse(access.getTeam().isManageJoinRequests());
    assertFalse(access.getTeam().isManageShopUsers());
    assertTrue(access.getModules().values().stream().noneMatch(Boolean.TRUE::equals));
    assertFalse(access.getStockCorrection().isCanApprove());
  }

  @Test
  void aSuperUserIsNeverTreatedAsShopOwner() {
    // stray data: legacy shopId makes hasOwnerAccess true
    when(membershipService.hasOwnerAccess("staff-1", "shop-1")).thenReturn(true);
    when(userAccountRepository.findById("staff-1"))
        .thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER, "shop-1")));

    BaseException e = assertThrows(BaseException.class,
        () -> rbacService.getAdminView("staff-1", "shop-1"));
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
  }

  @Test
  void managersKeepTheirTeamAccess() {
    UserShopMembership m = new UserShopMembership();
    m.setUserId("u-2");
    m.setShopId("shop-1");
    m.setRole(UserRole.MANAGER);
    m.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
    m.setActive(true);
    when(membershipService.hasAccess("u-2", "shop-1")).thenReturn(true);
    when(userAccountRepository.findById("u-2"))
        .thenReturn(Optional.of(account("u-2", UserRole.MANAGER, "shop-1")));
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("u-2", "shop-1")).thenReturn(Optional.of(m));
    when(policyRepository.findByShopId("shop-1")).thenReturn(Optional.empty());

    ShopAccessResponse access = rbacService.getEffectiveAccess("u-2", "shop-1");

    assertTrue(access.getTeam().isManageShopUsers());
    assertTrue(access.getTeam().isManageInvitations());
  }
}
