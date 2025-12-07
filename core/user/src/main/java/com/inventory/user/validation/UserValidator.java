package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.user.AddUserRequest;
import com.inventory.user.rest.dto.user.UpdateUserRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class UserValidator {

  public void validateAddUserRequest(String shopId, AddUserRequest request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Name is required");
    }
    if (!StringUtils.hasText(request.getEmail())) {
      throw new ValidationException("Email is required");
    }
    if (request.getRole() == null) {
      throw new ValidationException("Role is required");
    }
  }

  public void validateUpdateUserRequest(String shopId, String userId, UpdateUserRequest request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (!StringUtils.hasText(userId)) {
      throw new ValidationException("User ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
  }

  public void validateUserBelongsToShop(UserAccount account, String shopId, String userId) {
    if (account == null || !shopId.equals(account.getShopId())) {
      throw new ValidationException(String.format("User with ID %s not found in shop %s", userId, shopId));
    }
  }

  public void validateDeactivateRequest(String shopId, String userId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (!StringUtils.hasText(userId)) {
      throw new ValidationException("User ID is required");
    }
  }

  /**
   * Validates the accept invite request
   *
   * @param inviteId The ID of the invite to validate
   * @param request  The accept invite request containing the password
   */
  public void validateAcceptInviteRequest(String inviteId, Object request) {
    // Validate request parameters
    if (!StringUtils.hasText(inviteId)) {
      throw new ValidationException("Invite ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request body is required");
    }

    // Use reflection to get the password field to avoid dependency on specific request class
    try {
      String password = (String) request.getClass().getMethod("getPassword").invoke(request);

      // Validate password
      if (!StringUtils.hasText(password)) {
        throw new ValidationException("Password is required");
      }
      if (password.length() < 8) {
        throw new ValidationException("Password must be at least 8 characters long");
      }
    } catch (Exception e) {
      throw new ValidationException("Invalid request format");
    }
  }

  public void validateLastAdminDeactivation(UserAccount account, long adminCount) {
    if (account.isActive() && UserRole.ADMIN.equals(account.getRole()) && adminCount <= 1) {
      throw new ValidationException("Cannot deactivate the last admin user in the shop");
    }
  }
}
