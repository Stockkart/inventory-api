package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.rest.dto.request.AdminChangePasswordRequest;
import com.inventory.user.rest.dto.request.AdminCreateRequest;
import com.inventory.user.rest.dto.request.AdminResetPasswordRequest;
import com.inventory.user.rest.dto.request.AdminUserActiveRequest;
import com.inventory.user.service.AdminCredentials;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Input rules for admin accounts, sign-in passwords and admin-to-admin actions. */
@Component
public class AdminUserValidator {

  public static final int MIN_PASSWORD_LENGTH = 10;
  public static final int MAX_PASSWORD_LENGTH = 128;
  public static final int MAX_NAME_LENGTH = 60;
  public static final int MAX_REASON_LENGTH = 200;

  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  public void validateCreate(AdminCreateRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    validateEmail(request.getEmail());
    validateName(request.getName());
  }

  public void validateEmail(String email) {
    String normalized = AdminCredentials.normalizeEmail(email);
    if (normalized == null || !EMAIL.matcher(normalized).matches()) {
      throw new ValidationException("Enter a valid email address");
    }
  }

  public void validateName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Name must be 1 to " + MAX_NAME_LENGTH + " characters");
    }
  }

  public void validatePassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH
        || password.length() > MAX_PASSWORD_LENGTH) {
      throw new ValidationException("Password must be " + MIN_PASSWORD_LENGTH + " to "
          + MAX_PASSWORD_LENGTH + " characters");
    }
  }

  public void validateChangePassword(AdminChangePasswordRequest request) {
    if (request == null || request.getCurrentPassword() == null) {
      throw new ValidationException("Current password is incorrect");
    }
    validatePassword(request.getNewPassword());
  }

  public void validateResetCompletion(AdminResetPasswordRequest request) {
    if (request == null || !StringUtils.hasText(request.getToken())) {
      throw new ValidationException("The reset link is invalid or has expired");
    }
    validatePassword(request.getNewPassword());
  }

  /** A reason is optional; when given it is at most {@value #MAX_REASON_LENGTH} characters. */
  public void validateReason(String reason) {
    if (reason != null && reason.trim().length() > MAX_REASON_LENGTH) {
      throw new ValidationException("Reason must be at most " + MAX_REASON_LENGTH + " characters");
    }
  }

  public void validateSetActive(AdminUserActiveRequest request) {
    if (request == null || request.getActive() == null) {
      throw new ValidationException("active is required");
    }
    validateReason(request.getReason());
  }

  /** Turning an admin off: never yourself, and at least one admin stays active. */
  public void validateDisable(String targetAdminId, String actorAdminId, long activeAdmins) {
    if (targetAdminId.equals(actorAdminId)) {
      throw new ValidationException("You cannot turn off your own account");
    }
    if (activeAdmins <= 1) {
      throw new ValidationException("At least one admin must stay active");
    }
  }

  public void validateReset(String targetAdminId, String actorAdminId, String reason) {
    if (targetAdminId.equals(actorAdminId)) {
      throw new ValidationException("Use Change password for your own account");
    }
    validateReason(reason);
  }
}
