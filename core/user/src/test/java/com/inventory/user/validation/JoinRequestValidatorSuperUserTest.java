package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.SendJoinRequestRequest;
import org.junit.jupiter.api.Test;

/** A2/A3: join requests cannot ask for SUPER_USER or come from a SUPER_USER account. */
class JoinRequestValidatorSuperUserTest {

  final JoinRequestValidator validator = new JoinRequestValidator();

  private UserAccount account(UserRole role) {
    UserAccount a = new UserAccount();
    a.setUserId("u-1");
    a.setRole(role);
    return a;
  }

  @Test
  void aJoinRequestCannotAskForSuperUser() {
    SendJoinRequestRequest r = new SendJoinRequestRequest();
    r.setOwnerEmail("owner@example.com");
    r.setShopId("shop-1");
    r.setRole(UserRole.SUPER_USER);
    assertThrows(ValidationException.class, () -> validator.validateSendJoinRequest("u-1", r));
  }

  @Test
  void aSuperUserCannotAskToJoinAShop() {
    assertThrows(ValidationException.class,
        () -> validator.validateRequesterCanJoinShop(account(UserRole.SUPER_USER)));
    assertDoesNotThrow(() -> validator.validateRequesterCanJoinShop(account(UserRole.CASHIER)));
  }
}
