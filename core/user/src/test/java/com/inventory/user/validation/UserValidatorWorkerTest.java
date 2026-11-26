package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.AddUserRequest;
import com.inventory.user.rest.dto.request.UpdateUserRequest;
import org.junit.jupiter.api.Test;

/** worker-app R2.4: the Users page can neither add a WORKER nor turn a member into one. */
class UserValidatorWorkerTest {

  final UserValidator validator = new UserValidator();

  private UserAccount account(UserRole role) {
    UserAccount a = new UserAccount();
    a.setUserId("u-1");
    a.setRole(role);
    return a;
  }

  @Test
  void workerIsAWorkerRoleAndNotAPlatformRole() {
    assertTrue(UserRole.WORKER.isWorkerRole());
    assertFalse(UserRole.WORKER.isPlatformRole());
    for (UserRole role : UserRole.values()) {
      if (role != UserRole.WORKER) {
        assertFalse(role.isWorkerRole(), role.name());
      }
    }
    // isPlatformRole is unchanged: SUPER_USER only
    assertTrue(UserRole.SUPER_USER.isPlatformRole());
    assertFalse(UserRole.CASHIER.isPlatformRole());
  }

  @Test
  void addUserCannotCreateAWorker() {
    AddUserRequest r = new AddUserRequest();
    r.setName("Priya");
    r.setEmail("priya@example.com");
    r.setRole(UserRole.WORKER);

    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateAddUserRequest("shop-1", r));
    assertEquals(UserValidator.WORKER_ROLE_MESSAGE, e.getMessage());

    r.setRole(UserRole.CASHIER);
    assertDoesNotThrow(() -> validator.validateAddUserRequest("shop-1", r));
  }

  @Test
  void aRoleChangeCannotSetWorker() {
    UpdateUserRequest r = new UpdateUserRequest();
    r.setRole(UserRole.WORKER);

    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateUpdateUserRequest("shop-1", "u-1", r));
    assertEquals(UserValidator.WORKER_ROLE_MESSAGE, e.getMessage());

    r.setRole(UserRole.MANAGER);
    assertDoesNotThrow(() -> validator.validateUpdateUserRequest("shop-1", "u-1", r));
    r.setRole(null);
    assertDoesNotThrow(() -> validator.validateUpdateUserRequest("shop-1", "u-1", r));
  }

  @Test
  void membershipCreationAcceptsWorkerAndRefusesPlatformRolesOnly() {
    // worker join-request approval creates a WORKER membership through addMembership
    assertDoesNotThrow(() -> validator.validateCanJoinShop(account(UserRole.WORKER), UserRole.WORKER));
    assertThrows(ValidationException.class,
        () -> validator.validateCanJoinShop(account(UserRole.WORKER), UserRole.SUPER_USER));
    assertThrows(ValidationException.class,
        () -> validator.validateCanJoinShop(account(UserRole.SUPER_USER), UserRole.WORKER));
  }
}
