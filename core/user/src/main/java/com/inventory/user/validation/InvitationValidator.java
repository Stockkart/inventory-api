package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.InvitationStatus;
import com.inventory.user.domain.model.UserRole;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Component
public class InvitationValidator {

  public void validateSendInvitationRequest(String shopId, String inviterUserId, Object request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (!StringUtils.hasText(inviterUserId)) {
      throw new ValidationException("Inviter user ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request body is required");
    }

    // Use reflection to get fields to avoid dependency on specific request class
    try {
      String inviteeEmail = (String) request.getClass().getMethod("getInviteeEmail").invoke(request);
      UserRole role = (UserRole) request.getClass().getMethod("getRole").invoke(request);

      if (!StringUtils.hasText(inviteeEmail)) {
        throw new ValidationException("Invitee email is required");
      }
      if (role == null) {
        throw new ValidationException("Role is required");
      }
      if (!isValidRole(role)) {
        throw new ValidationException("Invalid role. Must be one of: ADMIN, MANAGER, CASHIER");
      }
    } catch (ValidationException e) {
      throw e;
    } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
      throw new ValidationException("Invalid request format");
    }
  }

  public void validateAcceptInvitationRequest(String invitationId) {
    if (!StringUtils.hasText(invitationId)) {
      throw new ValidationException("Invitation ID is required");
    }
  }

  public void validateInvitationForCurrentUser(Invitation invitation, String userId) {
    if (invitation == null || !userId.equals(invitation.getInviteeUserId())) {
      throw new ValidationException("This invitation is not for the current user");
    }
  }

  public void validateInvitationPending(Invitation invitation) {
    if (invitation == null || !InvitationStatus.PENDING.name().equals(invitation.getStatus())) {
      throw new ValidationException("Invitation is not in PENDING status");
    }
  }

  public void validateInvitationNotExpired(Invitation invitation) {
    if (invitation != null && invitation.getExpiresAt() != null
        && invitation.getExpiresAt().isBefore(Instant.now())) {
      throw new ValidationException("Invitation has expired");
    }
  }

  private boolean isValidRole(UserRole role) {
    return role == UserRole.ADMIN || role == UserRole.MANAGER || role == UserRole.CASHIER;
  }
}

