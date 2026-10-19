package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.UserShopMembershipMapper;
import com.inventory.user.rest.dto.response.UserShopListResponse;
import com.inventory.user.validation.UserValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A3: no membership is ever created for a SUPER_USER account or with the SUPER_USER role. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserShopMembershipServiceSuperUserTest {

  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private MetricsWrapper metrics;
  @Spy private UserShopMembershipMapper userShopMembershipMapper =
      Mappers.getMapper(UserShopMembershipMapper.class);
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private UserShopMembershipService membershipService;

  private UserAccount account(String userId, UserRole role) {
    UserAccount account = new UserAccount();
    account.setUserId(userId);
    account.setRole(role);
    account.setActive(true);
    return account;
  }

  @Test
  void aSuperUserAccountCannotBeAddedToAShop() {
    when(userAccountRepository.findById("staff-1"))
        .thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER)));

    assertThrows(ValidationException.class, () -> membershipService.addMembership(
        "staff-1", "shop-1", UserRole.CASHIER, UserShopMembershipService.RELATIONSHIP_INVITED));
    assertThrows(ValidationException.class, () -> membershipService.addMembership(
        "staff-1", "shop-1", UserRole.OWNER, UserShopMembershipService.RELATIONSHIP_OWNER));
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void theSuperUserRoleCannotBeGivenToAShopMember() {
    when(userAccountRepository.findById("u-1")).thenReturn(Optional.of(account("u-1", UserRole.CASHIER)));

    assertThrows(ValidationException.class, () -> membershipService.addMembership(
        "u-1", "shop-1", UserRole.SUPER_USER, UserShopMembershipService.RELATIONSHIP_INVITED));
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void aShopPersonStillGetsAMembership() {
    when(userAccountRepository.findById("u-1")).thenReturn(Optional.of(account("u-1", UserRole.CASHIER)));
    when(membershipRepository.save(any(UserShopMembership.class))).thenAnswer(i -> i.getArgument(0));

    UserShopMembership saved = membershipService.addMembership(
        "u-1", "shop-1", UserRole.MANAGER, UserShopMembershipService.RELATIONSHIP_INVITED);

    assertSame(UserRole.MANAGER, saved.getRole());
    verify(membershipRepository).save(saved);
  }

  @Test
  void aSuperUserWithNoShopHasNoShops() {
    when(membershipRepository.findByUserIdAndActiveTrue("staff-1")).thenReturn(List.of());
    when(userAccountRepository.findById("staff-1"))
        .thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER)));

    UserShopListResponse shops = membershipService.getShopsForUser("staff-1");

    assertTrue(shops.getData().isEmpty());
  }

  @Test
  void aSuperUserWithNoShopHasNoShopAccess() {
    when(membershipRepository.existsByUserIdAndShopIdAndActiveTrue("staff-1", "shop-1")).thenReturn(false);
    when(userAccountRepository.findById("staff-1"))
        .thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER)));

    // what AuthenticationInterceptor asks for an X-Shop-Id header
    assertFalse(membershipService.hasAccess("staff-1", "shop-1"));
    assertFalse(membershipService.hasAccess("staff-1", null));
  }
}
