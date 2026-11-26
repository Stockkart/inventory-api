package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.SendInvitationRequest;
import org.junit.jupiter.api.Test;

/** worker-app R2.4: an invitation never grants WORKER. */
class InvitationValidatorWorkerTest {

  final InvitationValidator validator = new InvitationValidator();

  @Test
  void anInvitationCannotGrantWorker() {
    SendInvitationRequest r = new SendInvitationRequest();
    r.setInviteeEmail("priya@example.com");
    r.setRole(UserRole.WORKER);

    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateSendInvitationRequest("shop-1", "owner-1", r));
    assertEquals(UserValidator.WORKER_ROLE_MESSAGE, e.getMessage());

    r.setRole(UserRole.CASHIER);
    assertDoesNotThrow(() -> validator.validateSendInvitationRequest("shop-1", "owner-1", r));
  }

  @Test
  void aWorkerAccountMayStillBeInvitedAsStaff() {
    // only Stockkart staff accounts are refused as invitees; a worker account can be promoted by
    // invitation like any other login
    UserAccount worker = new UserAccount();
    worker.setUserId("w-1");
    worker.setRole(UserRole.WORKER);
    assertDoesNotThrow(() -> validator.validateInviteeCanJoinShop(worker));
  }
}
