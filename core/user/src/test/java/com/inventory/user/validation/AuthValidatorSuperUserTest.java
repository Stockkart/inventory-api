package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.SignupRequest;
import org.junit.jupiter.api.Test;

/** A2: signup cannot grant SUPER_USER. */
class AuthValidatorSuperUserTest {

  final AuthValidator validator = new AuthValidator();

  private SignupRequest emailSignup(UserRole role) {
    SignupRequest r = new SignupRequest();
    r.setName("Asha");
    r.setEmail("asha@example.com");
    r.setPassword("long-enough-password");
    r.setPhone("9999999999");
    r.setRole(role);
    return r;
  }

  @Test
  void emailSignupCannotAskForSuperUser() {
    assertThrows(ValidationException.class,
        () -> validator.validateSignupRequest(emailSignup(UserRole.SUPER_USER)));
  }

  @Test
  void googleSignupCannotAskForSuperUser() {
    SignupRequest r = new SignupRequest();
    r.setIdToken("google-id-token");
    r.setSignupType("google");
    r.setRole(UserRole.SUPER_USER);
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }

  @Test
  void signupWithAShopRoleStillWorks() {
    assertDoesNotThrow(() -> validator.validateSignupRequest(emailSignup(UserRole.CASHIER)));
  }
}
