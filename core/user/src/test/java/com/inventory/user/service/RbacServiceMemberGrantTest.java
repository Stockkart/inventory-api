package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
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
import com.inventory.user.rest.dto.request.UpdateMemberPermissionsRequest;
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
 * Only the core product-search fields can be granted to a member. Granting anything else (rates,
 * default rate, discounts, schemes) used to return 200 and be dropped, so the owner never knew the
 * member still could not edit it; it is now a 400.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacServiceMemberGrantTest {

  private static final String SHOP = "shop-1";

  @Mock private ShopRbacPolicyRepository policyRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private MetricsWrapper metrics;
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private RbacService rbacService;

  private UserShopMembership membership;

  @BeforeEach
  void ownerEditsACashier() {
    ShopRbacPolicyDocument policy = new ShopRbacPolicyDocument();
    policy.setShopId(SHOP);
    policy.setProductSearchEditMode(ProductSearchEditMode.PERMISSION_BASED);
    when(policyRepository.findByShopId(SHOP)).thenReturn(Optional.of(policy));

    UserAccount owner = new UserAccount();
    owner.setUserId("owner-1");
    owner.setRole(UserRole.OWNER);
    when(userAccountRepository.findById("owner-1")).thenReturn(Optional.of(owner));
    when(membershipService.hasOwnerAccess("owner-1", SHOP)).thenReturn(true);

    UserAccount cashier = new UserAccount();
    cashier.setUserId("cashier-1");
    cashier.setRole(UserRole.CASHIER);
    when(userAccountRepository.findById("cashier-1")).thenReturn(Optional.of(cashier));
    membership = new UserShopMembership();
    membership.setUserId("cashier-1");
    membership.setShopId(SHOP);
    membership.setRole(UserRole.CASHIER);
    membership.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
    membership.setActive(true);
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("cashier-1", SHOP))
        .thenReturn(Optional.of(membership));
  }

  private static UpdateMemberPermissionsRequest grant(String... fields) {
    UpdateMemberPermissionsRequest request = new UpdateMemberPermissionsRequest();
    request.setProductSearchEditableFields(new LinkedHashSet<>(List.of(fields)));
    return request;
  }

  @Test
  void coreFieldsAreGranted() {
    rbacService.updateMemberPermissions("owner-1", SHOP, "cashier-1", grant("location", "maximumRetailPrice"));

    assertEquals(
        Set.of("location", "maximumRetailPrice"),
        membership.getPermissions().getProductSearchEditableFields());
  }

  @Test
  void aFieldThatCannotBeGrantedIsRefusedAndNothingIsSaved() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> rbacService.updateMemberPermissions(
            "owner-1", SHOP, "cashier-1", grant("location", "rates")));

    assertEquals("Product search field cannot be granted: rates", e.getMessage());
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void aNonGrantableFieldTheMemberAlreadyHeldIsDroppedSoTheScreenCanStillSave() {
    MemberPermissions stored = new MemberPermissions();
    stored.setProductSearchEditableFields(new LinkedHashSet<>(List.of("defaultRate", "location")));
    membership.setPermissions(stored);

    rbacService.updateMemberPermissions(
        "owner-1", SHOP, "cashier-1", grant("defaultRate", "location", "hsn"));

    assertEquals(Set.of("location", "hsn"), membership.getPermissions().getProductSearchEditableFields());
  }
}
