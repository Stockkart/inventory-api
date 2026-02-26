package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.joinrequest.AcceptRejectJoinRequestRequest;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestRequest;
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
    // Enum validation ensures only ACCEPT or REJECT are valid
  }

  private boolean isValidRole(UserRole role) {
    // OWNER cannot be requested via join request
    return role == UserRole.ADMIN || role == UserRole.MANAGER || role == UserRole.CASHIER;
  }
}

