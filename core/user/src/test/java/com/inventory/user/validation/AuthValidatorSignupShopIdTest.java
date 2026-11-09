package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.SignupRequest;
import org.junit.jupiter.api.Test;

/** Signup must not put an account into an existing shop (legacy shopId = owner access). */
class AuthValidatorSignupShopIdTest {

  final AuthValidator validator = new AuthValidator();

  private SignupRequest emailSignup() {
    SignupRequest r = new SignupRequest();
    r.setName("Asha");
    r.setEmail("asha@example.com");
    r.setPassword("long-enough-password");
    r.setPhone("9999999999");
    r.setRole(UserRole.CASHIER);
    return r;
  }

  @Test
  void emailSignupCannotNameAShop() {
    SignupRequest r = emailSignup();
    r.setShopId("64b000000000000000000001");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }

  @Test
  void googleSignupCannotNameAShop() {
    SignupRequest r = new SignupRequest();
    r.setIdToken("google-id-token");
    r.setSignupType("google");
    r.setRole(UserRole.CASHIER);
    r.setShopId("64b000000000000000000001");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }

  @Test
  void aBlankShopIdIsDropped() {
    SignupRequest r = emailSignup();
    r.setShopId("  ");
    assertDoesNotThrow(() -> validator.validateSignupRequest(r));
    assertNull(r.getShopId());
  }
}
