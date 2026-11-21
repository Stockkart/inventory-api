package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.InvitationStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.InvitationRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.InvitationMapper;
import com.inventory.user.rest.dto.request.SendInvitationRequest;
import com.inventory.user.validation.InvitationValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A2/A3: invitations neither grant SUPER_USER nor bring a SUPER_USER account into a shop. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvitationServiceSuperUserTest {

  @Mock private InvitationRepository invitationRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private InvitationMapper invitationMapper;
  @Mock private UserShopMembershipService membershipService;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private MetricsWrapper metrics;
  @Spy private InvitationValidator invitationValidator = new InvitationValidator();
  @InjectMocks private InvitationService invitationService;

  private UserAccount account(String userId, String email, UserRole role) {
    UserAccount account = new UserAccount();
    account.setUserId(userId);
    account.setEmail(email);
    account.setRole(role);
    account.setActive(true);
    return account;
  }

  private SendInvitationRequest invite(String email, UserRole role) {
    SendInvitationRequest request = new SendInvitationRequest();
    request.setInviteeEmail(email);
    request.setRole(role);
    return request;
  }

  @Test
  void anOwnerCannotInviteSomeoneAsSuperUser() {
    assertThrows(ValidationException.class, () -> invitationService.sendInvitation(
        "shop-1", "owner-1", invite("someone@example.com", UserRole.SUPER_USER)));
    verify(invitationRepository, never()).save(any());
  }

  @Test
  void aSuperUserAccountCannotBeInvitedToAShop() {
    when(userAccountRepository.findById("owner-1"))
        .thenReturn(Optional.of(account("owner-1", "owner@example.com", UserRole.OWNER)));
    when(membershipService.hasOwnerAccess("owner-1", "shop-1")).thenReturn(true);
    when(userAccountRepository.findByEmail("staff@stockkart.example"))
        .thenReturn(Optional.of(account("staff-1", "staff@stockkart.example", UserRole.SUPER_USER)));

    assertThrows(ValidationException.class, () -> invitationService.sendInvitation(
        "shop-1", "owner-1", invite("staff@stockkart.example", UserRole.CASHIER)));
    verify(invitationRepository, never()).save(any());
  }

  @Test
  void aSuperUserAccountCannotAcceptAnInvitation() {
    // e.g. an invitation sent before the account was made SUPER_USER
    Invitation invitation = new Invitation();
    invitation.setInvitationId("inv-1");
    invitation.setInviteeUserId("staff-1");
    invitation.setShopId("shop-1");
    invitation.setRole(UserRole.CASHIER);
    invitation.setStatus(InvitationStatus.PENDING.name());
    UserAccount staff = account("staff-1", "staff@stockkart.example", UserRole.SUPER_USER);
    when(invitationRepository.findById("inv-1")).thenReturn(Optional.of(invitation));
    when(userAccountRepository.findById("staff-1")).thenReturn(Optional.of(staff));
    // the real addMembership rejects SUPER_USER accounts (UserShopMembershipServiceSuperUserTest)
    when(membershipService.addMembership(eq("staff-1"), eq("shop-1"), any(), anyString()))
        .thenThrow(new ValidationException("Stockkart staff accounts cannot be added to a shop"));

    assertThrows(ValidationException.class, () -> invitationService.acceptInvitation("inv-1", "staff-1"));
    verify(userAccountRepository, never()).save(any());
    verify(invitationRepository, never()).save(any());
  }
}
