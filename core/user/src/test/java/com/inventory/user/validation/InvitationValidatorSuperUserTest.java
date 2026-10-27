package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.SendInvitationRequest;
import org.junit.jupiter.api.Test;

/** A2/A3: invitations cannot grant SUPER_USER or invite a SUPER_USER account. */
class InvitationValidatorSuperUserTest {

  final InvitationValidator validator = new InvitationValidator();

  private UserAccount account(UserRole role) {
    UserAccount a = new UserAccount();
    a.setUserId("u-1");
    a.setRole(role);
    return a;
  }

  @Test
  void anInvitationCannotGrantSuperUser() {
    SendInvitationRequest r = new SendInvitationRequest();
    r.setInviteeEmail("asha@example.com");
    r.setRole(UserRole.SUPER_USER);
    assertThrows(ValidationException.class,
        () -> validator.validateSendInvitationRequest("shop-1", "owner-1", r));
    r.setRole(UserRole.ADMIN);
    assertDoesNotThrow(() -> validator.validateSendInvitationRequest("shop-1", "owner-1", r));
  }

  @Test
  void aSuperUserCannotBeInvited() {
    assertThrows(ValidationException.class,
        () -> validator.validateInviteeCanJoinShop(account(UserRole.SUPER_USER)));
    assertDoesNotThrow(() -> validator.validateInviteeCanJoinShop(account(UserRole.OWNER)));
  }
}
