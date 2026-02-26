package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
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
import java.util.ArrayList;
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

  @Autowired
  private UserShopMembershipRepository membershipRepository;

  @Autowired
  private UserShopMembershipService membershipService;

  public UserListResponse listUsers(String shopId) {
    try {
      userValidator.validateShopId(shopId);

      log.debug("Retrieving users for shop: {}", shopId);
      List<UserDto> users = new ArrayList<>();

      // Primary: from memberships (multi-shop)
      for (UserShopMembership m : membershipRepository.findByShopIdAndActiveTrue(shopId)) {
        UserAccount account = userAccountRepository.findById(m.getUserId()).orElse(null);
        if (account != null) {
          UserDto dto = userMapper.toDto(account);
          dto.setRole(m.getRole());
          users.add(dto);
        }
      }

      // Backward compat: legacy users (shopId on UserAccount, no membership yet)
      for (UserAccount account : userAccountRepository.findByShopId(shopId)) {
        boolean alreadyAdded = users.stream().anyMatch(u -> u.getUserId().equals(account.getUserId()));
        if (!alreadyAdded) {
          users.add(userMapper.toDto(account));
        }
      }

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

      // Find the user and verify they belong to the specified shop (multi-shop: use membership)
      UserAccount account = userAccountRepository.findById(userId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
      userValidator.validateUserBelongsToShopByMembership(
          membershipService.hasAccess(userId, shopId), userId, shopId);

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

      // Update role if provided (multi-shop: update membership role; sync account.role if active shop)
      if (request.getRole() != null) {
        var membershipOpt = membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId);
        if (membershipOpt.isPresent()) {
          var m = membershipOpt.get();
          if (!request.getRole().equals(m.getRole())) {
            m.setRole(request.getRole());
            membershipRepository.save(m);
            isUpdated = true;
          }
        }
        if (shopId.equals(account.getShopId())) {
          account.setRole(request.getRole());
          account.setUpdatedAt(Instant.now());
          userAccountRepository.save(account);
        }
        if (isUpdated) {
          log.info("Updated user with ID: {} role in shop: {}", userId, shopId);
        }
      }
      if (!isUpdated) {
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

      // Verify user belongs to the specified shop (multi-shop: use membership)
      userValidator.validateUserBelongsToShopByMembership(
          membershipService.hasAccess(userId, shopId), userId, shopId);

      // Check if this is the last admin (use per-shop role from membership)
      if (account.isActive()) {
        var membership = membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId);
        UserRole roleInShop = membership.map(UserShopMembership::getRole).orElse(account.getRole());
        if (UserRole.ADMIN.equals(roleInShop)) {
          long adminCount = membershipRepository.findByShopIdAndActiveTrue(shopId).stream()
              .filter(m -> UserRole.ADMIN.equals(m.getRole()))
              .map(UserShopMembership::getUserId)
              .filter(uid -> {
                UserAccount u = userAccountRepository.findById(uid).orElse(null);
                return u != null && u.isActive();
              })
              .count();
          userValidator.validateLastAdminDeactivation(account, adminCount);
        }
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

