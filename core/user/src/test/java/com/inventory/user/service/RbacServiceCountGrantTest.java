package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.ProductSearchEditMode;
import com.inventory.user.domain.model.ShopRbacPolicyDocument;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.ShopRbacPolicyRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.validation.UserValidator;
import java.util.LinkedHashSet;
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

/**
 * A lot edit cannot change stock, so stock quantity ({@code count}) is not a product-search field
 * an owner can grant; a stored {@code count} grant gives nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceCountGrantTest {

  private static final String SHOP = "shop-1";

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
    policy.setShopId(SHOP);
    policy.setProductSearchEditMode(ProductSearchEditMode.PERMISSION_BASED);
    when(policyRepository.findByShopId(SHOP)).thenReturn(Optional.of(policy));
    when(membershipService.hasAccess("cashier-1", SHOP)).thenReturn(true);
  }

  private void cashierGranted(String... fields) {
    UserAccount account = new UserAccount();
    account.setUserId("cashier-1");
    account.setRole(UserRole.CASHIER);
    when(userAccountRepository.findById("cashier-1")).thenReturn(Optional.of(account));
    MemberPermissions permissions = new MemberPermissions();
    permissions.setProductSearchEditableFields(new LinkedHashSet<>(List.of(fields)));
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
  void countIsNotAGrantableField() {
    assertFalse(RbacService.CORE_PRODUCT_SEARCH_FIELDS.contains("count"));
  }

  @Test
  void aStoredCountGrantIsIgnored() {
    cashierGranted("count", "location");

    assertEquals(
        List.of("location"),
        rbacService.getEffectiveAccess("cashier-1", SHOP).getProductSearch().getEditableFields());
  }

  @Test
  void aMemberGrantedOnlyCountHasNoEditAccess() {
    cashierGranted("count");

    assertFalse(rbacService.getEffectiveAccess("cashier-1", SHOP).getProductSearch().isCanEdit());
    assertThrows(BaseException.class, () -> rbacService.validateProductSearchFieldUpdates(
        "cashier-1", SHOP, Set.of("location")));
  }
}
