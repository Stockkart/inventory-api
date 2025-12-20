package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.user.DeactivateUserResponse;
import com.inventory.user.rest.dto.user.UpdateUserRequest;
import com.inventory.user.rest.dto.user.UserDto;
import com.inventory.user.rest.dto.user.UserListResponse;
import com.inventory.user.rest.mapper.UserMapper;
import com.inventory.user.validation.UserValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@Transactional
public class UserService {

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired
  private UserMapper userMapper;

  @Autowired
  private UserValidator userValidator;

  public UserListResponse listUsers(String shopId) {
    try {
      userValidator.validateDeactivateRequest(shopId, ""); // Only validate shopId

      log.debug("Retrieving users for shop: {}", shopId);
      List<UserDto> users = userAccountRepository.findByShopId(shopId).stream()
          .map(userMapper::toDto)
          .toList();

      log.debug("Found {} users for shop: {}", users.size(), shopId);
      UserListResponse response = new UserListResponse();
      response.setData(users);
      return response;

    } catch (ValidationException e) {
      log.warn("Validation error in listUsers: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while listing users for shop {}: {}", shopId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving user list");
    } catch (Exception e) {
      log.error("Unexpected error while listing users for shop {}: {}", shopId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve users");
    }
  }

  public UserDto updateUser(String shopId, String userId, UpdateUserRequest request) {
    try {
      userValidator.validateUpdateUserRequest(shopId, userId, request);

      log.debug("Updating user with ID: {} in shop: {}", userId, shopId);

      // Find the user and verify they belong to the specified shop
      UserAccount account = userAccountRepository.findById(userId)
          .filter(user -> shopId.equals(user.getShopId()))
          .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

      boolean isUpdated = false;

      // Update name if provided
      if (request.getName() != null && !request.getName().trim().isEmpty()) {
        String newName = request.getName().trim();
        if (!newName.equals(account.getName())) {
          account.setName(newName);
          account.setUpdatedAt(Instant.now());
          isUpdated = true;
        }
      }

      // Update role if provided and valid
      if (request.getRole() != null && !request.getRole().equals(account.getRole())) {
        // Add any role validation logic here if needed
        account.setRole(request.getRole());
        isUpdated = true;
        account.setUpdatedAt(Instant.now());
        userAccountRepository.save(account);
        log.info("Updated user with ID: {} in shop: {}", userId, shopId);
      } else {
        log.debug("No changes detected for user with ID: {}", userId);
      }

      return userMapper.toDto(account);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Failed to update user: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating user with ID {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating user");
    } catch (Exception e) {
      log.error("Unexpected error while updating user with ID {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to update user");
    }
  }

  public DeactivateUserResponse deactivate(String shopId, String userId) {
    try {
      userValidator.validateDeactivateRequest(shopId, userId);

      log.debug("Deactivating user with ID: {} in shop: {}", userId, shopId);

      // Find the user
      UserAccount account = userAccountRepository.findById(userId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

      // Verify user belongs to the specified shop and check admin status
      userValidator.validateUserBelongsToShop(account, shopId, userId);

      // Check if this is the last admin
      if (account.isActive() && UserRole.ADMIN.equals(account.getRole())) {
        long adminCount = userAccountRepository.findByShopId(shopId).stream()
            .filter(u -> UserRole.ADMIN.equals(u.getRole()) && u.isActive())
            .count();
        userValidator.validateLastAdminDeactivation(account, adminCount);
      }

      if (account.isActive()) {
        account.setActive(false);
        userAccountRepository.save(account);
        log.info("Deactivated user with ID: {} in shop: {}", userId, shopId);
      } else {
        log.debug("User with ID: {} is already deactivated", userId);
      }

      DeactivateUserResponse response = new DeactivateUserResponse();
      response.setUserId(account.getUserId());
      response.setActive(account.isActive());
      return response;

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Failed to deactivate user: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while deactivating user with ID {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error deactivating user");
    } catch (Exception e) {
      log.error("Unexpected error while deactivating user with ID {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to deactivate user");
    }
  }
}

