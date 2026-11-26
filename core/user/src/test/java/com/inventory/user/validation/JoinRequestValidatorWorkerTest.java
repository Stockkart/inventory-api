package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.SendJoinRequestRequest;
import org.junit.jupiter.api.Test;

/** worker-app R2.4: the staff join request cannot ask for WORKER (that is the Stockkart Team flow). */
class JoinRequestValidatorWorkerTest {

  final JoinRequestValidator validator = new JoinRequestValidator();

  @Test
  void aStaffJoinRequestCannotAskForWorker() {
    SendJoinRequestRequest r = new SendJoinRequestRequest();
    r.setOwnerEmail("owner@example.com");
    r.setShopId("shop-1");
    r.setRole(UserRole.WORKER);

    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateSendJoinRequest("u-1", r));
    assertEquals(UserValidator.WORKER_ROLE_MESSAGE, e.getMessage());

    r.setRole(UserRole.MANAGER);
    assertDoesNotThrow(() -> validator.validateSendJoinRequest("u-1", r));
  }

  @Test
  void ownerIsStillRefusedWithTheExistingMessage() {
    SendJoinRequestRequest r = new SendJoinRequestRequest();
    r.setOwnerEmail("owner@example.com");
    r.setShopId("shop-1");
    r.setRole(UserRole.OWNER);

    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateSendJoinRequest("u-1", r));
    assertEquals("Invalid role. Must be one of: ADMIN, MANAGER, CASHIER", e.getMessage());
  }
}
