package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.rest.dto.request.ChangePasswordRequest;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.request.LogoutRequest;
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
  }

  public void validateSignupRequest(SignupRequest request) {
    if (request == null) {
      throw new ValidationException("Signup request cannot be null");
    }

    // Either idToken (OAuth) or email/password/name must be provided
    boolean hasIdToken = StringUtils.hasText(request.getIdToken());
    boolean hasEmail = StringUtils.hasText(request.getEmail());
    boolean hasPassword = StringUtils.hasText(request.getPassword());
    boolean hasName = StringUtils.hasText(request.getName());
    boolean hasSignupType = StringUtils.hasText(request.getSignupType());

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
    if (request.getRole() == null) {
      throw new ValidationException("Role is required");
    }
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
    if (request == null || request.getEmail() == null || request.getEmail().trim().isEmpty()) {
      throw new ValidationException("Email is required");
    }
    if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
      throw new ValidationException("Password is required");
    }
  }
}
