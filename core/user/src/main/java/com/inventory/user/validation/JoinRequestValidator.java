package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.JoinRequestStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.AcceptRejectJoinRequestRequest;
import com.inventory.user.rest.dto.request.SendJoinRequestRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class JoinRequestValidator {

  public void validateSendJoinRequest(String userId, SendJoinRequestRequest request) {
    if (!StringUtils.hasText(userId)) {
      throw new ValidationException("User ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    if (!StringUtils.hasText(request.getOwnerEmail())) {
      throw new ValidationException("Owner email is required");
    }
    if (!StringUtils.hasText(request.getShopId())) {
      throw new ValidationException("Shop ID is required");
    }
    if (request.getRole() == null) {
      throw new ValidationException("Role is required");
    }
    if (!isValidRole(request.getRole())) {
      throw new ValidationException("Invalid role. Must be one of: ADMIN, MANAGER, CASHIER");
    }
  }

  public void validateAcceptRejectRequest(AcceptRejectJoinRequestRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    if (request.getAction() == null) {
      throw new ValidationException("Action is required");
    }
  }

  public void validateOwnerHasAccessToShop(boolean hasAccess) {
    if (!hasAccess) {
      throw new ValidationException("You do not have owner access to this shop");
    }
  }

  public void validateOwnerHasShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Owner does not have a shop associated. Specify shopId.");
    }
  }

  public void validateJoinRequestBelongsToOwner(boolean belongs) {
    if (!belongs) {
      throw new ValidationException("This join request does not belong to your shop");
    }
  }

  public void validateJoinRequestPending(JoinRequestStatus status) {
    if (status != JoinRequestStatus.PENDING) {
      throw new ValidationException("Join request is not in PENDING status");
    }
  }

  /** Stockkart staff (SUPER_USER) accounts are never shop people, so they cannot ask to join one. */
  public void validateRequesterCanJoinShop(UserAccount requester) {
    if (requester != null && requester.getRole() != null && requester.getRole().isPlatformRole()) {
      throw new ValidationException("Stockkart staff accounts cannot join a shop");
    }
  }

  private boolean isValidRole(UserRole role) {
    // OWNER cannot be requested via join request; SUPER_USER is not a shop role at all
    return role == UserRole.ADMIN || role == UserRole.MANAGER || role == UserRole.CASHIER;
  }
}

