package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.JoinRequest;
import com.inventory.user.domain.model.JoinRequestStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.JoinRequestRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.mapper.JoinRequestMapper;
import com.inventory.user.rest.dto.request.AcceptRejectJoinRequestRequest;
import com.inventory.user.rest.dto.request.SendJoinRequestRequest;
import com.inventory.user.rest.dto.response.JoinRequestAction;
import com.inventory.user.validation.JoinRequestValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A3: a SUPER_USER account can neither ask to join a shop nor be accepted into one. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JoinRequestServiceSuperUserTest {

  @Mock private JoinRequestRepository joinRequestRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private JoinRequestMapper joinRequestMapper;
  @Mock private UserShopMembershipService membershipService;
  @Mock private MetricsWrapper metrics;
  @Spy private JoinRequestValidator joinRequestValidator = new JoinRequestValidator();
  @InjectMocks private JoinRequestService joinRequestService;

  private UserAccount account(String userId, UserRole role) {
    UserAccount a = new UserAccount();
    a.setUserId(userId);
    a.setEmail(userId + "@example.com");
    a.setRole(role);
    a.setActive(true);
    return a;
  }

  @Test
  void aSuperUserCannotSendAJoinRequest() {
    when(userAccountRepository.findById("staff-1")).thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER)));
    SendJoinRequestRequest request = new SendJoinRequestRequest();
    request.setOwnerEmail("owner-1@example.com");
    request.setShopId("shop-1");
    request.setRole(UserRole.CASHIER);

    assertThrows(ValidationException.class, () -> joinRequestService.sendJoinRequest("staff-1", request));
    verify(joinRequestRepository, never()).save(any());
  }

  @Test
  void anOwnerCannotAcceptAJoinRequestFromASuperUser() {
    // e.g. a request sent before the account was made SUPER_USER
    JoinRequest joinRequest = new JoinRequest();
    joinRequest.setRequestId("jr-1");
    joinRequest.setShopId("shop-1");
    joinRequest.setUserId("staff-1");
    joinRequest.setRequestedRole(UserRole.CASHIER.name());
    joinRequest.setStatus(JoinRequestStatus.PENDING);
    when(userAccountRepository.findById("owner-1")).thenReturn(Optional.of(account("owner-1", UserRole.OWNER)));
    when(userAccountRepository.findById("staff-1")).thenReturn(Optional.of(account("staff-1", UserRole.SUPER_USER)));
    when(joinRequestRepository.findById("jr-1")).thenReturn(Optional.of(joinRequest));
    when(membershipService.hasOwnerAccess("owner-1", "shop-1")).thenReturn(true);
    AcceptRejectJoinRequestRequest accept = new AcceptRejectJoinRequestRequest();
    accept.setAction(JoinRequestAction.ACCEPT);

    assertThrows(ValidationException.class,
        () -> joinRequestService.processJoinRequest("jr-1", "owner-1", accept));
    verify(membershipService, never()).addMembership(anyString(), anyString(), any(), anyString());
    verify(userAccountRepository, never()).save(any());
    verify(joinRequestRepository, never()).save(any());
  }
}
