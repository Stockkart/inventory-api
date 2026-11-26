package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.AuthSource;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.request.SignupRequest;
import org.junit.jupiter.api.Test;

/** Worker-app R1.2, R1.4, R1.8: sign-up and sign-in rules for the Stockkart Team app. */
class AuthValidatorWorkerSignupTest {

  final AuthValidator validator = new AuthValidator();

  private SignupRequest workerEmailSignup() {
    SignupRequest r = new SignupRequest();
    r.setName("Priya Sharma");
    r.setEmail("priya@example.com");
    r.setPassword("long-enough-password");
    r.setPhone("+91 98765 43210");
    r.setRole(UserRole.WORKER);
    r.setSource("worker-app");
    return r;
  }

  private SignupRequest workerGoogleSignup() {
    SignupRequest r = new SignupRequest();
    r.setIdToken("google-id-token");
    r.setSignupType("google");
    r.setRole(UserRole.WORKER);
    r.setPhone("9876543210");
    r.setSource("worker-app");
    return r;
  }

  // --- phone ---

  @Test
  void workerEmailSignupIsAcceptedAndThePhoneIsNormalised() {
    SignupRequest r = workerEmailSignup();
    assertDoesNotThrow(() -> validator.validateSignupRequest(r));
    assertEquals("9876543210", r.getPhone());
    assertEquals(AuthSource.WORKER_APP, r.getSource());
  }

  @Test
  void workerGoogleSignupRequiresAPhone() {
    SignupRequest r = workerGoogleSignup();
    r.setPhone(null);
    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
    assertEquals("Phone is required", e.getMessage());

    r.setPhone("   ");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }

  @Test
  void workerGoogleSignupWithAPhoneIsAcceptedAndNormalised() {
    SignupRequest r = workerGoogleSignup();
    r.setPhone("+91-98765-43210");
    assertDoesNotThrow(() -> validator.validateSignupRequest(r));
    assertEquals("9876543210", r.getPhone());
  }

  @Test
  void staffGoogleSignupStillAllowsNoPhone() {
    SignupRequest r = workerGoogleSignup();
    r.setRole(UserRole.CASHIER);
    r.setPhone(null);
    assertDoesNotThrow(() -> validator.validateSignupRequest(r));
  }

  @Test
  void workerEmailSignupRefusesAPhoneThatIsNotAnIndianMobile() {
    SignupRequest r = workerEmailSignup();
    r.setPhone("09876543210");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }

  @Test
  void normalizeIndianMobileDelegatesToTheSharedRule() {
    assertEquals("9876543210", AuthValidator.normalizeIndianMobile("+91 98765 43210"));
    assertThrows(ValidationException.class, () -> AuthValidator.normalizeIndianMobile("12345"));
  }

  // --- name ---

  @Test
  void workerNameIsTrimmedAndMustBeTwoToOneHundredCharacters() {
    SignupRequest r = workerEmailSignup();
    r.setName("  Priya  ");
    validator.validateSignupRequest(r);
    assertEquals("Priya", r.getName());

    r.setName(" P ");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));

    r.setName("Ab");
    assertDoesNotThrow(() -> validator.validateSignupRequest(r));

    r.setName("a".repeat(100));
    assertDoesNotThrow(() -> validator.validateSignupRequest(r));

    r.setName("a".repeat(101));
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }

  @Test
  void validateNameRule() {
    assertEquals("Ab", AuthValidator.validateName(" Ab "));
    assertTrue(AuthValidator.isValidName("a".repeat(100)));
    assertFalse(AuthValidator.isValidName("a"));
    assertFalse(AuthValidator.isValidName(" " + "a".repeat(101) + " "));
    assertFalse(AuthValidator.isValidName(null));
    assertThrows(ValidationException.class, () -> AuthValidator.validateName("x"));
  }

  // --- source ---

  @Test
  void sourceDefaultsToWebWhenAbsent() {
    SignupRequest s = workerEmailSignup();
    s.setSource(null);
    validator.validateSignupRequest(s);
    assertEquals(AuthSource.WEB, s.getSource());

    LoginRequest l = new LoginRequest();
    l.setEmail("priya@example.com");
    l.setPassword("long-enough-password");
    validator.validateLoginRequest(l);
    assertEquals(AuthSource.WEB, l.getSource());
  }

  @Test
  void sourceIsNormalisedAndRefusedWhenUnknown() {
    LoginRequest l = new LoginRequest();
    l.setEmail("priya@example.com");
    l.setPassword("long-enough-password");
    l.setSource(" Worker-App ");
    validator.validateLoginRequest(l);
    assertEquals(AuthSource.WORKER_APP, l.getSource());

    l.setSource("ios-widget");
    assertThrows(ValidationException.class, () -> validator.validateLoginRequest(l));

    SignupRequest s = workerEmailSignup();
    s.setSource("ios-widget");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(s));
  }

  @Test
  void workerSignupStillCannotNameAShop() {
    SignupRequest r = workerEmailSignup();
    r.setShopId("64b000000000000000000001");
    assertThrows(ValidationException.class, () -> validator.validateSignupRequest(r));
  }
}
