package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.inventory.metrics.MetricsWrapper;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The owner may edit every product-search field whatever the shop's member policy
 * (validateProductSearchFieldUpdates lets the owner through), so the owner's effective access says
 * FULL_EDIT. The frontend filters the Edit dialog's payload by this; reporting the shop's
 * PERMISSION_BASED policy made it drop schemes, discounts and the rest for the owner.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceOwnerProductSearchTest {

  @Mock private ShopRbacPolicyRepository policyRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private MetricsWrapper metrics;
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private RbacService rbacService;

  @BeforeEach
  void permissionBasedShop() {
    ShopRbacPolicyDocument policy = new ShopRbacPolicyDocument();
    policy.setShopId("shop-1");
    policy.setProductSearchEditMode(ProductSearchEditMode.PERMISSION_BASED);
    when(policyRepository.findByShopId("shop-1")).thenReturn(Optional.of(policy));
  }

  private void member(String userId, UserRole role, String relationship) {
    UserAccount a = new UserAccount();
    a.setUserId(userId);
    a.setRole(role);
    a.setShopId("shop-1");
    a.setActive(true);
    UserShopMembership m = new UserShopMembership();
    m.setUserId(userId);
    m.setShopId("shop-1");
    m.setRole(role);
    m.setRelationship(relationship);
    m.setActive(true);
    when(membershipService.hasAccess(userId, "shop-1")).thenReturn(true);
    when(userAccountRepository.findById(userId)).thenReturn(Optional.of(a));
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, "shop-1"))
        .thenReturn(Optional.of(m));
  }

  @Test
  void theOwnerOfAPermissionBasedShopHasFullEdit() {
    member("owner-1", UserRole.OWNER, UserShopMembershipService.RELATIONSHIP_OWNER);

    ShopAccessResponse access = rbacService.getEffectiveAccess("owner-1", "shop-1");

    assertTrue(access.isOwner());
    assertEquals(ProductSearchEditMode.FULL_EDIT, access.getProductSearch().getEditMode());
    assertTrue(access.getProductSearch().isCanEditAll());
  }

  @Test
  void aMemberOfAPermissionBasedShopKeepsTheShopPolicy() {
    member("cashier-1", UserRole.CASHIER, UserShopMembershipService.RELATIONSHIP_INVITED);

    ShopAccessResponse access = rbacService.getEffectiveAccess("cashier-1", "shop-1");

    assertFalse(access.isOwner());
    assertEquals(ProductSearchEditMode.PERMISSION_BASED, access.getProductSearch().getEditMode());
  }
}
