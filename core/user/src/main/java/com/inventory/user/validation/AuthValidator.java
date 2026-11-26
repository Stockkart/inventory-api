package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.PhoneNumbers;
import com.inventory.user.rest.dto.request.AuthSource;
import com.inventory.user.rest.dto.request.ChangePasswordRequest;
import com.inventory.user.rest.dto.request.ForgotPasswordRequest;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.request.LogoutRequest;
import com.inventory.user.rest.dto.request.ResetPasswordRequest;
import com.inventory.user.rest.dto.request.SignupRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthValidator {

  public void validateLoginRequest(LoginRequest request) {
    if (request == null) {
      throw new ValidationException("Login request cannot be null");
    }

    // Either idToken (OAuth) or email/password must be provided
    boolean hasIdToken = StringUtils.hasText(request.getIdToken());
    boolean hasEmail = StringUtils.hasText(request.getEmail());
    boolean hasPassword = StringUtils.hasText(request.getPassword());
    boolean hasLoginType = StringUtils.hasText(request.getLoginType());

    if (hasIdToken) {
      // OAuth authentication (Google/Facebook) - idToken and loginType are required
      if (!hasLoginType) {
        throw new ValidationException("loginType is required when idToken is provided");
      }
      String loginType = request.getLoginType().toLowerCase();
      if (!loginType.equals("google") && !loginType.equals("facebook")) {
        throw new ValidationException("loginType must be either 'google' or 'facebook'");
      }
    } else {
      // Email/password authentication
      if (!hasEmail) {
        throw new ValidationException("Email is required");
      }
      if (!hasPassword) {
        throw new ValidationException("Password is required");
      }
    }
    request.setSource(AuthSource.normalise(request.getSource()));
  }

  public void validateSignupRequest(SignupRequest request) {
    if (request == null) {
      throw new ValidationException("Signup request cannot be null");
    }

    // SUPER_USER (Stockkart staff) is granted only by the operator script, never by signup.
    if (request.getRole() != null && request.getRole().isPlatformRole()) {
      throw new ValidationException("Invalid role. SUPER_USER cannot be requested at signup");
    }
    // A new account joins a shop only by registering one, an accepted invitation or an accepted
    // join request. A shopId in the body would set UserAccount.shopId, which the legacy access
    // checks (hasAccess / hasOwnerAccess) and AuthenticationInterceptor treat as owning that shop.
    if (StringUtils.hasText(request.getShopId())) {
      throw new ValidationException(
          "shopId cannot be set at signup. Register a shop or join one after signing up");
    }
    request.setShopId(null);
    request.setSource(AuthSource.normalise(request.getSource()));

    // Either idToken (OAuth) or email/password/name must be provided
    boolean hasIdToken = StringUtils.hasText(request.getIdToken());
    boolean hasEmail = StringUtils.hasText(request.getEmail());
    boolean hasPassword = StringUtils.hasText(request.getPassword());
    boolean hasName = StringUtils.hasText(request.getName());
    boolean hasSignupType = StringUtils.hasText(request.getSignupType());
    boolean worker = request.getRole() != null && request.getRole().isWorkerRole();

    if (hasIdToken) {
      // OAuth signup (Google/Facebook) - idToken, signupType, and role are required
      if (!hasSignupType) {
        throw new ValidationException("signupType is required when idToken is provided");
      }
      String signupType = request.getSignupType().toLowerCase();
      if (!signupType.equals("google") && !signupType.equals("facebook")) {
        throw new ValidationException("signupType must be either 'google' or 'facebook'");
      }
      if (request.getRole() == null) {
        throw new ValidationException("Role is required for OAuth signup");
      }
      // Phone is optional on OAuth for staff, required for a worker (R1.4: an account is never
      // created with an empty phone number); normalised whenever present
      if (worker && !StringUtils.hasText(request.getPhone())) {
        throw new ValidationException("Phone is required");
      }
      if (StringUtils.hasText(request.getPhone())) {
        request.setPhone(normalizeIndianMobile(request.getPhone()));
      }
      return;
    }

    // Email/password signup
    if (!hasEmail) {
      throw new ValidationException("Email is required");
    }
    if (!hasPassword) {
      throw new ValidationException("Password is required");
    }
    if (request.getPassword().length() < 8) {
      throw new ValidationException("Password must be at least 8 characters long");
    }
    if (!hasName) {
      throw new ValidationException("Name is required");
    }
    if (worker) {
      request.setName(validateName(request.getName()));
    }
    if (request.getRole() == null) {
      throw new ValidationException("Role is required");
    }
    if (!StringUtils.hasText(request.getPhone())) {
      throw new ValidationException("Phone is required");
    }
    request.setPhone(normalizeIndianMobile(request.getPhone()));
  }

  /** Shortest full name a worker may sign up with, after trimming (R1.2). */
  public static final int NAME_MIN = 2;

  /** Longest full name a worker may sign up with, after trimming (R1.2). */
  public static final int NAME_MAX = 100;

  /**
   * A worker's full name: 2–100 characters after trimming (R1.2). Shared with the worker profile
   * edit so the rule lives once (Property 1). Trimming removes Unicode whitespace ({@link
   * String#strip}, the predicate {@code StringUtils.hasText} uses), so a name made only of
   * ideographic or em spaces is refused here exactly as the "Name is required" check refuses it.
   *
   * @return the trimmed name
   * @throws ValidationException when the trimmed name is shorter than 2 or longer than 100
   */
  public static String validateName(String raw) {
    String name = raw == null ? "" : raw.strip();
    if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
      throw new ValidationException("Name must be between 2 and 100 characters");
    }
    return name;
  }

  /** True when {@link #validateName} accepts {@code raw}. */
  public static boolean isValidName(String raw) {
    String name = raw == null ? "" : raw.strip();
    return name.length() >= NAME_MIN && name.length() <= NAME_MAX;
  }

  /**
   * Accepts 10 digits or +91 / 91 prefix; stores canonical 10-digit form. The rule itself is {@link
   * PhoneNumbers#normalise} so HR's possible-match and the app use the same one.
   */
  public static String normalizeIndianMobile(String raw) {
    return PhoneNumbers.normalise(raw)
        .orElseThrow(
            () ->
                new ValidationException(
                    "Phone must be a valid Indian number (10 digits or +91 format)"));
  }

  public void validateLogoutRequest(LogoutRequest request) {
    if (request == null) {
      throw new ValidationException("Logout request cannot be null");
    }
    if (!StringUtils.hasText(request.getUserId())) {
      throw new ValidationException("User ID is required");
    }
    if ((request.getDeviceId() == null || request.getDeviceId().trim().isEmpty()) &&
        (request.getAccessToken() == null || request.getAccessToken().trim().isEmpty())) {
      throw new ValidationException("Either deviceId or accessToken must be provided");
    }
  }

  public void validateLogoutParams(String userId, String accessToken) {
    if (userId == null || userId.trim().isEmpty()) {
      throw new ValidationException("User ID is required");
    }
    if (accessToken == null || accessToken.trim().isEmpty()) {
      throw new ValidationException("Access token is required");
    }
  }

  public void validateChangePasswordRequest(ChangePasswordRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    if (request.getCurrentPassword() == null || request.getCurrentPassword().isEmpty()) {
      throw new ValidationException("Current password is required");
    }
    if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
      throw new ValidationException("Password is required");
    }
    if (request.getPassword().trim().length() < 8) {
      throw new ValidationException("Password must be at least 8 characters long");
    }
  }

  public void validateForgotPasswordRequest(ForgotPasswordRequest request) {
    if (request == null || request.getEmail() == null || request.getEmail().trim().isEmpty()) {
      throw new ValidationException("Email is required");
    }
  }

  public void validateResetPasswordRequest(ResetPasswordRequest request) {
    if (request == null || request.getToken() == null || request.getToken().trim().isEmpty()) {
      throw new ValidationException("Reset token is required");
    }
    if (request.getNewPassword() == null || request.getNewPassword().trim().isEmpty()) {
      throw new ValidationException("New password is required");
    }
    if (request.getNewPassword().length() < 8) {
      throw new ValidationException("Password must be at least 8 characters long");
    }
  }
}
