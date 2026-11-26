package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.UserShopMembershipMapper;
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

/** worker-app R1.5, R2.5: a WORKER membership can be created, and it is not a staff seat. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserShopMembershipServiceWorkerTest {

  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private MetricsWrapper metrics;
  @Spy private UserShopMembershipMapper userShopMembershipMapper =
      Mappers.getMapper(UserShopMembershipMapper.class);
  @Spy private UserValidator userValidator = new UserValidator();
  @InjectMocks private UserShopMembershipService membershipService;

  private static UserShopMembership membership(UserRole role, boolean active) {
    UserShopMembership m = new UserShopMembership();
    m.setUserId("u-" + role + "-" + active);
    m.setShopId("shop-1");
    m.setRole(role);
    m.setActive(active);
    return m;
  }

  @Test
  void aWorkerMembershipIsAccepted() {
    UserAccount worker = new UserAccount();
    worker.setUserId("w-1");
    worker.setRole(UserRole.WORKER);
    worker.setActive(true);
    when(userAccountRepository.findById("w-1")).thenReturn(Optional.of(worker));
    when(membershipRepository.save(any(UserShopMembership.class))).thenAnswer(i -> i.getArgument(0));

    UserShopMembership saved = membershipService.addMembership(
        "w-1", "shop-1", UserRole.WORKER, UserShopMembershipService.RELATIONSHIP_INVITED);

    assertSame(UserRole.WORKER, saved.getRole());
    assertEquals(UserShopMembershipService.RELATIONSHIP_INVITED, saved.getRelationship());
    assertNull(saved.getEndedAt());
    verify(membershipRepository).save(saved);
  }

  @Test
  void staffCountLeavesWorkersOut() {
    when(membershipRepository.findByShopIdAndActiveTrue("shop-1")).thenReturn(List.of(
        membership(UserRole.OWNER, true),
        membership(UserRole.MANAGER, true),
        membership(UserRole.CASHIER, true),
        membership(UserRole.WORKER, true),
        membership(UserRole.WORKER, true),
        membership(UserRole.WORKER, true)));

    assertEquals(3, membershipService.staffCountForShop("shop-1"));
  }

  @Test
  void aMembershipWithoutARoleCountsAsStaff() {
    // rows written before roles were stored on the membership are staff logins
    when(membershipRepository.findByShopIdAndActiveTrue("shop-1"))
        .thenReturn(List.of(membership(null, true), membership(UserRole.WORKER, true)));

    assertEquals(1, membershipService.staffCountForShop("shop-1"));
  }

  @Test
  void aShopOfWorkersOnlyUsesNoSeat() {
    when(membershipRepository.findByShopIdAndActiveTrue("shop-1"))
        .thenReturn(List.of(membership(UserRole.WORKER, true)));

    assertEquals(0, membershipService.staffCountForShop("shop-1"));
  }
}
