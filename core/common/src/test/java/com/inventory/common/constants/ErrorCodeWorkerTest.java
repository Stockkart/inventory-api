package com.inventory.common.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Worker-app codes 8035–8047 and the two "too many attempts" texts (design, API table). 8042, 8043
 * and 8046 are gaps: their legacy pairing codes were removed (D11) and nothing is renumbered.
 */
class ErrorCodeWorkerTest {

  @Test
  @DisplayName("every error code number is unique")
  void codesAreUnique() {
    Set<Integer> seen = new HashSet<>();
    for (ErrorCode c : ErrorCode.values()) {
      assertTrue(seen.add(c.getCode()), "duplicate code " + c.getCode() + " on " + c);
    }
  }

  @Test
  @DisplayName("worker-app codes carry the numbers, statuses and texts of the design table")
  void workerCodesMatchTheDesign() {
    assertCode(ErrorCode.HR_WORKER_HAS_SHOP, 8035, HttpStatus.CONFLICT,
        "You are already part of a shop. Leave it first.");
    assertCode(ErrorCode.HR_WORKER_REQUEST_PENDING, 8036, HttpStatus.CONFLICT,
        "You already have a request waiting.");
    assertCode(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP, 8037, HttpStatus.CONFLICT,
        "This login is already part of another shop.");
    assertCode(ErrorCode.HR_REQUEST_ALREADY_HANDLED, 8038, HttpStatus.CONFLICT,
        "This request was already handled");
    assertCode(ErrorCode.HR_MATCH_LINKED_ELSEWHERE, 8039, HttpStatus.CONFLICT,
        "%s is already linked to another login");
    assertCode(ErrorCode.HR_MATCH_EXITED, 8040, HttpStatus.UNPROCESSABLE_ENTITY,
        "%s has exited. Create a new employee instead.");
    assertCode(ErrorCode.HR_PAIRING_WRONG_EMPLOYEE, 8041, HttpStatus.FORBIDDEN,
        "This code is for a different employee. Ask your manager.");
    assertCode(ErrorCode.HR_TWO_CREDENTIALS, 8044, HttpStatus.BAD_REQUEST,
        "A request may carry a login or a phone signature, not both.");
    assertCode(ErrorCode.HR_WORKER_NOT_ACTIVE, 8045, HttpStatus.CONFLICT,
        "You are not part of a shop.");
    assertCode(ErrorCode.HR_WORKER_ONLY, 8047, HttpStatus.FORBIDDEN,
        "This app is for workers. Ask your manager to link your login to your employee record "
            + "from the Employees page.");
  }

  @Test
  @DisplayName("8042, 8043 and 8046 stay unused: the legacy pairing codes are gone, nothing moved")
  void legacyCodesAreGaps() {
    for (ErrorCode c : ErrorCode.values()) {
      assertTrue(c.getCode() != 8042 && c.getCode() != 8043 && c.getCode() != 8046,
          c.name() + " reuses a removed legacy code number");
    }
  }

  @Test
  @DisplayName("8028 keeps the punch-path text; the worker paths have their own")
  void tooManyAttemptsHasTwoTexts() {
    assertCode(ErrorCode.HR_TOO_MANY_ATTEMPTS, 8028, HttpStatus.TOO_MANY_REQUESTS,
        "Too many attempts, wait a minute");
    assertEquals("Too many attempts, try again later",
        ErrorCode.HR_TOO_MANY_ATTEMPTS_WORKER_MESSAGE);
  }

  @Test
  @DisplayName("4004 is the Google-login-without-an-account answer, in the user range, 401, the web's text (DD20)")
  void oauthAccountNotFoundIsAUserRangeCodeWithTheWebsText() {
    assertCode(ErrorCode.OAUTH_ACCOUNT_NOT_FOUND, 4004, HttpStatus.UNAUTHORIZED,
        "No account found for this Google account. Please sign up first.");
    int code = ErrorCode.OAUTH_ACCOUNT_NOT_FOUND.getCode();
    assertTrue(code >= 4000 && code < 5000, "user range, not an 80xx HR code");
    assertEquals(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS.getHttpStatus(),
        "the password path's code keeps its status");
  }

  private static void assertCode(ErrorCode c, int code, HttpStatus status, String message) {
    assertEquals(code, c.getCode(), c.name());
    assertEquals(status, c.getHttpStatus(), c.name());
    assertEquals(message, c.getMessage(), c.name());
  }
}
