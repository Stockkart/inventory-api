package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.UpdateUserRequest;
import org.junit.jupiter.api.Test;

/** A2/A3: shop-member role changes and membership creation never involve SUPER_USER. */
class UserValidatorSuperUserTest {

  final UserValidator validator = new UserValidator();

  private UserAccount account(UserRole role) {
    UserAccount a = new UserAccount();
    a.setUserId("u-1");
    a.setRole(role);
    return a;
  }

  @Test
  void aShopMemberRoleChangeCannotGrantSuperUser() {
    UpdateUserRequest r = new UpdateUserRequest();
    r.setRole(UserRole.SUPER_USER);
    assertThrows(ValidationException.class, () -> validator.validateUpdateUserRequest("shop-1", "u-1", r));
    r.setRole(UserRole.MANAGER);
    assertDoesNotThrow(() -> validator.validateUpdateUserRequest("shop-1", "u-1", r));
  }

  @Test
  void membershipCreationRejectsSuperUserAccountsAndRole() {
    assertThrows(ValidationException.class,
        () -> validator.validateCanJoinShop(account(UserRole.SUPER_USER), UserRole.CASHIER));
    assertThrows(ValidationException.class,
        () -> validator.validateCanJoinShop(account(UserRole.CASHIER), UserRole.SUPER_USER));
    assertDoesNotThrow(() -> validator.validateCanJoinShop(account(UserRole.CASHIER), UserRole.ADMIN));
    assertDoesNotThrow(() -> validator.validateCanJoinShop(null, UserRole.OWNER));
  }
}
