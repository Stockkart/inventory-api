package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.rest.dto.auth.AcceptInviteRequest;
import com.inventory.user.rest.dto.auth.LoginRequest;
import com.inventory.user.rest.dto.auth.LogoutRequest;
import com.inventory.user.rest.dto.auth.SignupRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Component
public class AuthValidator {

  public void validateLoginRequest(LoginRequest request) {
    if (request == null) {
      throw new ValidationException("Login request cannot be null");
    }
    
    // Either idToken (Google) or email/password must be provided
    boolean hasIdToken = StringUtils.hasText(request.getIdToken());
    boolean hasEmail = StringUtils.hasText(request.getEmail());
    boolean hasPassword = StringUtils.hasText(request.getPassword());
    
    if (hasIdToken) {
      // Google authentication - only idToken is required
      return;
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

  public void validateInvite(UserInvite invite) {
    if (invite == null) {
      throw new ValidationException("Invalid or expired invite");
    }
    if (invite.isAccepted()) {
      throw new ValidationException("Invite has already been used");
    }
    if (invite.getExpiresAt().isBefore(Instant.now())) {
      throw new ValidationException("Invite has expired");
    }
  }

  public void validateAcceptInviteRequest(AcceptInviteRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    if (!StringUtils.hasText(request.getPassword())) {
      throw new ValidationException("Password is required");
    }
    if (request.getPassword().length() < 8) {
      throw new ValidationException("Password must be at least 8 characters long");
    }
  }

  public void validateSignupRequest(SignupRequest request) {
    if (request == null) {
      throw new ValidationException("Signup request cannot be null");
    }
    
    // Either idToken (Google) or email/password/name must be provided
    boolean hasIdToken = StringUtils.hasText(request.getIdToken());
    boolean hasEmail = StringUtils.hasText(request.getEmail());
    boolean hasPassword = StringUtils.hasText(request.getPassword());
    boolean hasName = StringUtils.hasText(request.getName());
    
    if (hasIdToken) {
      // Google signup - idToken is required, role is optional (defaults to CASHIER)
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
}
