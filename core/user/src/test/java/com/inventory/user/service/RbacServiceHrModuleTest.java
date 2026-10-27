package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.MemberModulePermissions;
import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.ShopRbacPolicyRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.validation.UserValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Module {@code hr}: on by default for every role, off only by an explicit member switch. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceHrModuleTest {

  @Mock private ShopRbacPolicyRepository policyRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private MetricsWrapper metrics;
  @Mock private HrAccessAdapter hrAccessAdapter;
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private RbacService rbacService;

  @BeforeEach
  void setUp() {
    when(membershipService.hasAccess("u1", "s1")).thenReturn(true);
    when(policyRepository.findByShopId("s1")).thenReturn(Optional.empty());
    UserAccount account = new UserAccount();
    account.setUserId("u1");
    account.setRole(UserRole.CASHIER);
    when(userAccountRepository.findById("u1")).thenReturn(Optional.of(account));
  }

  private void member(UserRole role, Boolean hrSwitch, String relationship) {
    UserShopMembership m = new UserShopMembership();
    m.setUserId("u1");
    m.setShopId("s1");
    m.setRole(role);
    m.setRelationship(relationship);
    m.setActive(true);
    if (hrSwitch != null) {
      MemberModulePermissions modules = new MemberModulePermissions();
      modules.setHr(hrSwitch);
      m.setPermissions(new MemberPermissions(modules, null));
    }
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("u1", "s1"))
        .thenReturn(Optional.of(m));
  }

  @ParameterizedTest
  @EnumSource(value = UserRole.class, names = {"ADMIN", "MANAGER", "CASHIER"})
  void hrIsOnByDefaultForEveryShopRole(UserRole role) {
    member(role, null, UserShopMembershipService.RELATIONSHIP_INVITED);

    assertEquals(true, rbacService.getEffectiveAccess("u1", "s1").getModules()
        .get(RbacService.MODULE_HR));
  }

  @Test
  void hrIsOnForTheOwner() {
    member(UserRole.OWNER, null, UserShopMembershipService.RELATIONSHIP_OWNER);

    assertEquals(true, rbacService.getEffectiveAccess("u1", "s1").getModules()
        .get(RbacService.MODULE_HR));
  }

  @Test
  void aNullSwitchKeepsHrOn() {
    member(UserRole.MANAGER, null, UserShopMembershipService.RELATIONSHIP_INVITED);
    // a stored modules object without the hr field (every membership before HR shipped)
    UserShopMembership m = membershipRepository.findByUserIdAndShopIdAndActiveTrue("u1", "s1")
        .orElseThrow();
    m.setPermissions(new MemberPermissions(new MemberModulePermissions(), null));

    assertEquals(true, rbacService.getEffectiveAccess("u1", "s1").getModules()
        .get(RbacService.MODULE_HR));
  }

  @Test
  void anExplicitFalseSwitchTurnsHrOffAndRequireModuleDenies() {
    member(UserRole.ADMIN, false, UserShopMembershipService.RELATIONSHIP_INVITED);

    assertEquals(false, rbacService.getEffectiveAccess("u1", "s1").getModules()
        .get(RbacService.MODULE_HR));
    assertThrows(BaseException.class,
        () -> rbacService.requireModule("u1", "s1", RbacService.MODULE_HR));
  }

  @Test
  void requireModuleReturnsTheAccessItChecked() {
    member(UserRole.MANAGER, null, UserShopMembershipService.RELATIONSHIP_INVITED);

    ShopAccessResponse access = rbacService.requireModule("u1", "s1", RbacService.MODULE_HR);

    assertEquals("MANAGER", access.getRole());
  }

  @Test
  void theClientAccessCarriesTheHrBlock() {
    member(UserRole.MANAGER, null, UserShopMembershipService.RELATIONSHIP_INVITED);
    ShopAccessResponse.HrAccessDto hr =
        new ShopAccessResponse.HrAccessDto(List.of("VIEW_ATTENDANCE"), "e1");
    when(hrAccessAdapter.resolve(eq("u1"), eq("s1"), any())).thenReturn(hr);

    ShopAccessResponse access = rbacService.getEffectiveAccessForClient("u1", "s1");

    assertSame(hr, access.getHr());
  }

  @Test
  void anHrReadFailureFallsBackToNoHrAccess() {
    member(UserRole.MANAGER, null, UserShopMembershipService.RELATIONSHIP_INVITED);
    when(hrAccessAdapter.resolve(eq("u1"), eq("s1"), any()))
        .thenThrow(new RuntimeException("db down"));

    ShopAccessResponse access = rbacService.getEffectiveAccessForClient("u1", "s1");

    assertTrue(access.getHr().getPermissions().isEmpty());
    assertNull(access.getHr().getEmployeeId());
  }

  @Test
  void moduleChecksDoNotPayForTheHrBlock() {
    member(UserRole.MANAGER, null, UserShopMembershipService.RELATIONSHIP_INVITED);

    assertNull(rbacService.getEffectiveAccess("u1", "s1").getHr());
    assertTrue(rbacService.getEffectiveAccess("u1", "s1").getModules()
        .containsKey(RbacService.MODULE_HR));
    assertFalse(rbacService.getEffectiveAccess("u1", "s1").isOwner());
  }
}
