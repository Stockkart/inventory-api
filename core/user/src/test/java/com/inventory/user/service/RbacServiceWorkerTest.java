package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.MemberModulePermissions;
import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.ProductSearchEditMode;
import com.inventory.user.domain.model.ShopRbacPolicyDocument;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** worker-app R2.1, R2.3: a WORKER membership grants nothing on the staff side. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceWorkerTest {

  private static final List<String> ALL_MODULES = List.of(
      RbacService.MODULE_ACCOUNTING, RbacService.MODULE_ANALYTICS, RbacService.MODULE_MIS,
      RbacService.MODULE_TAXES, RbacService.MODULE_STOCK_CORRECTION, RbacService.MODULE_MARKETING,
      RbacService.MODULE_PAYMENT_PLAN, RbacService.MODULE_PRODUCT_SEARCH_EDIT,
      RbacService.MODULE_RATE_RULES_EDIT, RbacService.MODULE_HR);

  @Mock private ShopRbacPolicyRepository policyRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private MetricsWrapper metrics;
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private RbacService rbacService;

  private final UserShopMembership membership = new UserShopMembership();

  @BeforeEach
  void setUp() {
    UserAccount worker = new UserAccount();
    worker.setUserId("w1");
    worker.setRole(UserRole.WORKER);
    worker.setShopId("s1");
    worker.setActive(true);
    membership.setUserId("w1");
    membership.setShopId("s1");
    membership.setRole(UserRole.WORKER);
    membership.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
    membership.setActive(true);
    when(membershipService.hasAccess("w1", "s1")).thenReturn(true);
    when(userAccountRepository.findById("w1")).thenReturn(Optional.of(worker));
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("w1", "s1"))
        .thenReturn(Optional.of(membership));
    when(policyRepository.findByShopId("s1")).thenReturn(Optional.empty());
  }

  @Test
  void everyModuleIsOffIncludingHr() {
    ShopAccessResponse access = rbacService.getEffectiveAccess("w1", "s1");

    for (String module : ALL_MODULES) {
      assertEquals(Boolean.FALSE, access.getModules().get(module), module);
    }
    assertFalse(access.isOwner());
    assertFalse(access.isCanManageAccess());
    assertEquals(UserRole.WORKER.name(), access.getRole());
  }

  @Test
  void requireModuleRefusesEveryKeyWithAccessDenied() {
    for (String module : ALL_MODULES) {
      BaseException e = assertThrows(BaseException.class,
          () -> rbacService.requireModule("w1", "s1", module), module);
      assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode(), module);
    }
  }

  @Test
  void teamAccessIsOffExceptViewMyInvitations() {
    ShopAccessResponse.TeamAccessDto team = rbacService.getEffectiveAccess("w1", "s1").getTeam();

    assertFalse(team.isManageInvitations());
    assertFalse(team.isManageJoinRequests());
    assertFalse(team.isManageShopUsers());
    assertTrue(team.isViewMyInvitations());
    for (String key : List.of(RbacService.TEAM_MANAGE_INVITATIONS,
        RbacService.TEAM_MANAGE_JOIN_REQUESTS, RbacService.TEAM_MANAGE_SHOP_USERS)) {
      BaseException e = assertThrows(BaseException.class,
          () -> rbacService.requireTeamAccess("w1", "s1", key), key);
      assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode(), key);
    }
  }

  @Test
  void storedOverridesOnAWorkerMembershipGrantNothing() {
    // an owner could still save module grants on the row; they must not switch anything on
    MemberModulePermissions modules = new MemberModulePermissions();
    modules.setAccounting(true);
    modules.setAnalytics(true);
    modules.setMis(true);
    modules.setTaxes(true);
    modules.setStockCorrection(true);
    modules.setMarketing(true);
    modules.setPaymentPlan(true);
    modules.setProductSearchEdit(true);
    modules.setRateRulesEdit(true);
    modules.setHr(true);
    membership.setPermissions(new MemberPermissions(modules, null));

    ShopAccessResponse access = rbacService.getEffectiveAccess("w1", "s1");

    assertTrue(access.getModules().values().stream().noneMatch(Boolean.TRUE::equals));
    assertFalse(access.getProductSearch().isCanEdit());
    assertFalse(access.getStockCorrection().isCanApprove());
  }

  @Test
  void aFieldGrantUnderPermissionBasedPolicyGrantsAWorkerNoProductEdit() {
    // shrunk counterexample of Property 2: a non-empty core-field grant on a WORKER row under
    // PERMISSION_BASED used to turn productSearchEdit on and let the worker edit products
    ShopRbacPolicyDocument policy = new ShopRbacPolicyDocument();
    policy.setShopId("s1");
    policy.setProductSearchEditMode(ProductSearchEditMode.PERMISSION_BASED);
    when(policyRepository.findByShopId("s1")).thenReturn(Optional.of(policy));
    membership.setPermissions(new MemberPermissions(null, Set.of("name")));

    ShopAccessResponse access = rbacService.getEffectiveAccess("w1", "s1");

    assertFalse(access.getProductSearch().isCanEdit());
    assertFalse(access.getProductSearch().isCanEditAll());
    assertTrue(access.getProductSearch().getEditableFields().isEmpty());
    assertEquals(Boolean.FALSE, access.getModules().get(RbacService.MODULE_PRODUCT_SEARCH_EDIT));
    BaseException e = assertThrows(BaseException.class,
        () -> rbacService.requireModule("w1", "s1", RbacService.MODULE_PRODUCT_SEARCH_EDIT));
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> rbacService.validateProductSearchFieldUpdates("w1", "s1", Set.of("name")))
        .getErrorCode());
  }

  @Test
  void aCashierStillHasHrOnSoTheWorkerRuleIsNotOverReaching() {
    membership.setRole(UserRole.CASHIER);

    assertEquals(Boolean.TRUE,
        rbacService.getEffectiveAccess("w1", "s1").getModules().get(RbacService.MODULE_HR));
  }
}
