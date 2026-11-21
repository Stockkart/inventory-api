package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.rest.dto.request.AdminCreateRequest;
import com.inventory.user.rest.dto.request.AdminResetPasswordRequest;
import com.inventory.user.rest.dto.request.AdminUserActiveRequest;
import org.junit.jupiter.api.Test;

class AdminUserValidatorTest {

  private final AdminUserValidator validator = new AdminUserValidator();

  @Test
  void passwordLengthIsBounded() {
    assertDoesNotThrow(() -> validator.validatePassword("x".repeat(10)));
    assertDoesNotThrow(() -> validator.validatePassword("x".repeat(128)));
    assertThrows(ValidationException.class, () -> validator.validatePassword("x".repeat(9)));
    assertThrows(ValidationException.class, () -> validator.validatePassword("x".repeat(129)));
    assertThrows(ValidationException.class, () -> validator.validatePassword(null));
  }

  @Test
  void createNeedsAValidEmailAndAName() {
    assertDoesNotThrow(() -> validator.validateCreate(new AdminCreateRequest(" Ops@StockKart.in ", "Ops")));
    assertThrows(ValidationException.class, () -> validator.validateCreate(new AdminCreateRequest("ops", "Ops")));
    assertThrows(ValidationException.class,
        () -> validator.validateCreate(new AdminCreateRequest("ops@stockkart.in", " ")));
    assertThrows(ValidationException.class,
        () -> validator.validateCreate(new AdminCreateRequest("ops@stockkart.in", "x".repeat(61))));
    assertThrows(ValidationException.class, () -> validator.validateCreate(null));
  }

  @Test
  void nobodyTurnsThemselvesOffOrTheLastActiveAdmin() {
    assertThrows(ValidationException.class, () -> validator.validateDisable("a1", "a1", 3));
    assertThrows(ValidationException.class, () -> validator.validateDisable("a2", "a1", 1));
    assertDoesNotThrow(() -> validator.validateDisable("a2", "a1", 2));
    assertThrows(ValidationException.class, () -> validator.validateSetActive(new AdminUserActiveRequest(null, null)));
  }

  @Test
  void resetIsForOtherAdminsWithAShortReason() {
    assertThrows(ValidationException.class, () -> validator.validateReset("a1", "a1", null));
    assertThrows(ValidationException.class, () -> validator.validateReset("a2", "a1", "x".repeat(201)));
    assertDoesNotThrow(() -> validator.validateReset("a2", "a1", "left the team"));
  }

  @Test
  void resetCompletionNeedsATokenAndAnAcceptablePassword() {
    assertThrows(ValidationException.class,
        () -> validator.validateResetCompletion(new AdminResetPasswordRequest(" ", "a-brand-new-password")));
    assertThrows(ValidationException.class,
        () -> validator.validateResetCompletion(new AdminResetPasswordRequest("tok", "short")));
    assertDoesNotThrow(() -> validator.validateResetCompletion(new AdminResetPasswordRequest("tok", "a-brand-new-password")));
  }
}
