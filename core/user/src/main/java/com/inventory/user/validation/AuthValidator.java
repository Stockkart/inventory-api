package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.rest.dto.auth.AcceptInviteRequest;
import com.inventory.user.rest.dto.auth.LoginRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Component
public class AuthValidator {

  public void validateLoginRequest(LoginRequest request) {
    if (request == null) {
      throw new ValidationException("Login request cannot be null");
    }
    if (!StringUtils.hasText(request.getEmail())) {
      throw new ValidationException("Email is required");
    }
    if (!StringUtils.hasText(request.getPassword())) {
      throw new ValidationException("Password is required");
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
}
