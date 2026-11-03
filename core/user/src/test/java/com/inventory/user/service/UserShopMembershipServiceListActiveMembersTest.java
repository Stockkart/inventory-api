package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserShopMembershipServiceListActiveMembersTest {

  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @InjectMocks private UserShopMembershipService service;

  private static UserShopMembership membership(String userId, UserRole role, String rel) {
    UserShopMembership m = new UserShopMembership();
    m.setUserId(userId);
    m.setShopId("s1");
    m.setRole(role);
    m.setRelationship(rel);
    m.setActive(true);
    return m;
  }

  private static UserAccount account(String userId, String name, UserRole role) {
    UserAccount a = new UserAccount();
    a.setUserId(userId);
    a.setName(name);
    a.setRole(role);
    return a;
  }

  @Test
  void listsActiveMembersWithNameAndRoleButNoStaffAccounts() {
    when(membershipRepository.findByShopIdAndActiveTrue("s1")).thenReturn(List.of(
        membership("u1", UserRole.OWNER, "OWNER"),
        membership("u2", UserRole.CASHIER, null),
        membership("staff", null, "INVITED"),
        membership("gone", UserRole.MANAGER, "INVITED")));
    when(userAccountRepository.findAllById(anyIterable())).thenReturn(List.of(
        account("u1", "Asha", UserRole.OWNER),
        account("u2", "Ravi", UserRole.CASHIER),
        account("staff", "HQ", UserRole.SUPER_USER)));

    List<ShopMemberSummaryDto> members = service.listActiveMembers("s1");

    assertEquals(List.of(
        new ShopMemberSummaryDto("u1", "Asha", "OWNER", "OWNER"),
        new ShopMemberSummaryDto("u2", "Ravi", "CASHIER", "INVITED")), members);
  }

  @Test
  void noShopMeansNoMembers() {
    assertTrue(service.listActiveMembers(null).isEmpty());
  }
}
