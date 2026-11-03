package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.inventory.user.domain.model.MemberModulePermissions;
import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.ShopRbacPolicyRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.validation.UserValidator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceRateRulesEditTest {

  private static final String SHOP = "shop-1";

  @Mock private ShopRbacPolicyRepository policyRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private UserValidator userValidator;
  @InjectMocks private RbacService rbacService;

  @BeforeEach
  void setUp() {
    when(membershipService.hasAccess(anyString(), anyString())).thenReturn(true);
    when(policyRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
  }

  private void owner() {
    UserAccount account = new UserAccount();
    account.setUserId("owner-1");
    account.setShopId(SHOP);
    account.setRole(UserRole.OWNER);
    when(userAccountRepository.findById("owner-1")).thenReturn(Optional.of(account));
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("owner-1", SHOP))
        .thenReturn(Optional.empty());
  }

  private void cashier(MemberPermissions permissions) {
    UserAccount account = new UserAccount();
    account.setUserId("cashier-1");
    account.setRole(UserRole.CASHIER);
    when(userAccountRepository.findById("cashier-1")).thenReturn(Optional.of(account));
    UserShopMembership membership = new UserShopMembership();
    membership.setUserId("cashier-1");
    membership.setShopId(SHOP);
    membership.setRole(UserRole.CASHIER);
    membership.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
    membership.setActive(true);
    membership.setPermissions(permissions);
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("cashier-1", SHOP))
        .thenReturn(Optional.of(membership));
  }

  @Test
  void ownerAlwaysHasRateRulesEdit() {
    owner();

    assertTrue(rbacService.canEditRates("owner-1", SHOP));
    assertTrue(rbacService.getEffectiveAccess("owner-1", SHOP).getModules()
        .get(RbacService.MODULE_RATE_RULES_EDIT));
  }

  @Test
  void cashierLacksItByDefault() {
    cashier(null);

    assertFalse(rbacService.canEditRates("cashier-1", SHOP));
    assertFalse(rbacService.getEffectiveAccess("cashier-1", SHOP).getModules()
        .get(RbacService.MODULE_RATE_RULES_EDIT));
  }

  @Test
  void ownerGrantTurnsItOn() {
    MemberPermissions permissions = new MemberPermissions();
    MemberModulePermissions modules = new MemberModulePermissions();
    modules.setRateRulesEdit(true);
    permissions.setModules(modules);
    cashier(permissions);

    assertTrue(rbacService.canEditRates("cashier-1", SHOP));
  }

  @Test
  void anExplicitFalseOverrideKeepsItOff() {
    MemberPermissions permissions = new MemberPermissions();
    MemberModulePermissions modules = new MemberModulePermissions();
    modules.setRateRulesEdit(false);
    permissions.setModules(modules);
    cashier(permissions);

    assertFalse(rbacService.canEditRates("cashier-1", SHOP));
  }
}
