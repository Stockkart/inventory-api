package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserInviteRepository;
import com.inventory.user.rest.dto.user.AcceptUserInviteRequest;
import com.inventory.user.rest.dto.user.AddUserRequest;
import com.inventory.user.rest.dto.user.AddUserResponse;
import com.inventory.user.rest.dto.user.DeactivateUserResponse;
import com.inventory.user.rest.dto.user.UpdateUserRequest;
import com.inventory.user.rest.dto.user.UserDto;
import com.inventory.user.rest.dto.user.UserListResponse;
import com.inventory.user.rest.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class UserService {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserInviteRepository userInviteRepository;

    @Autowired
    private UserMapper userMapper;

    public UserListResponse listUsers(String shopId) {
        try {
            if (!StringUtils.hasText(shopId)) {
                throw new ValidationException("Shop ID is required");
            }
            
            log.debug("Retrieving users for shop: {}", shopId);
            List<UserDto> users = userAccountRepository.findByShopId(shopId).stream()
                    .map(userMapper::toDto)
                    .toList();
                    
            log.debug("Found {} users for shop: {}", users.size(), shopId);
            return UserListResponse.builder()
                    .data(users)
                    .build();
                    
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

    public AddUserResponse addUser(String shopId, AddUserRequest request) {
        try {
            // Input validation
            if (!StringUtils.hasText(shopId)) {
                throw new ValidationException("Shop ID is required");
            }
            if (request == null) {
                throw new ValidationException("Request body is required");
            }
            if (!StringUtils.hasText(request.getEmail())) {
                throw new ValidationException("Email is required");
            }
            if (!StringUtils.hasText(request.getName())) {
                throw new ValidationException("Name is required");
            }
            if (request.getRole() == null) {
                throw new ValidationException("Role is required");
            }
            
            // Check if user with this email already exists in the shop
            boolean userExists = userAccountRepository.findByEmail(request.getEmail())
                    .filter(user -> shopId.equals(user.getShopId()))
                    .isPresent();
            
            if (userExists) {
                throw new ResourceExistsException("User with email " + request.getEmail() + " already exists in this shop");
            }
            
            // Check for existing pending invites for this email in the same shop
            // Note: This is a simplified check as we don't have a direct repository method for this
            boolean hasPendingInvite = userInviteRepository.findByToken(request.getEmail()) // Using email as a token for this example
                    .filter(invite -> !invite.isAccepted() && shopId.equals(invite.getShopId()))
                    .isPresent();
            
            if (hasPendingInvite) {
                throw new ResourceExistsException("A pending invite already exists for this email in the shop");
            }
            
            log.debug("Creating user invite for email: {} in shop: {}", request.getEmail(), shopId);
            
            UserInvite invite = UserInvite.builder()
                    .inviteId("invite-" + UUID.randomUUID())
                    .shopId(shopId)
                    .name(request.getName().trim())
                    .email(request.getEmail().toLowerCase().trim())
                    .role(request.getRole())
                    .token(UUID.randomUUID().toString())
                    .expiresAt(Instant.now().plusSeconds(7 * 24 * 3600)) // 7 days expiry
                    .accepted(false)
                    .build();
                    
            userInviteRepository.save(invite);
            
            log.info("Created user invite with ID: {} for email: {} in shop: {}", 
                    invite.getInviteId(), request.getEmail(), shopId);
                    
            return AddUserResponse.builder()
                    .inviteId(invite.getInviteId())
                    .message("Invite sent successfully")
                    .build();
                    
        } catch (ValidationException | ResourceExistsException e) {
            log.warn("Failed to add user: {}", e.getMessage());
            throw e;
        } catch (DuplicateKeyException e) {
            log.error("Duplicate key error while adding user: {}", e.getMessage(), e);
            throw new ResourceExistsException("A user or invite with this email already exists");
        } catch (DataAccessException e) {
            log.error("Database error while adding user: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error creating user invite");
        } catch (Exception e) {
            log.error("Unexpected error while adding user: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create user invite");
        }
    }

    public UserDto acceptInvite(String inviteId, AcceptUserInviteRequest request) {
        try {
            if (!StringUtils.hasText(inviteId)) {
                throw new ValidationException("Invite ID is required");
            }
            if (request == null) {
                throw new ValidationException("Request body is required");
            }
            if (!StringUtils.hasText(request.getPassword())) {
                throw new ValidationException("Password is required");
            }
            if (request.getPassword().length() < 8) {
                throw new ValidationException("Password must be at least 8 characters long");
            }
            
            log.debug("Processing invite acceptance for invite ID: {}", inviteId);
            
            // Find and validate the invite
            UserInvite invite = userInviteRepository.findById(inviteId)
                    .orElseThrow(() -> new ResourceNotFoundException("Invite", "id", inviteId));
                    
            if (invite.isAccepted()) {
                throw new ValidationException("This invite has already been accepted");
            }
            
            if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) {
                throw new ValidationException("This invite has expired");
            }
            
            // Check if user already exists with this email
            userAccountRepository.findByEmail(invite.getEmail())
                    .ifPresent(user -> {
                        throw new ResourceExistsException("User with this email already exists");
                    });
            
            // Mark invite as accepted
            invite.setAccepted(true);
            userInviteRepository.save(invite);
            
            log.info("Creating user account for accepted invite: {}", inviteId);
            
            // Create the user account
            UserAccount account = UserAccount.builder()
                    .userId("user-" + UUID.randomUUID())
                    .name(invite.getName())
                    .role(invite.getRole())
                    .shopId(invite.getShopId())
                    .email(invite.getEmail().toLowerCase().trim())
                    .password(request.getPassword()) // Note: In a real app, this should be hashed
                    .active(true)
                    .inviteAccepted(true)
                    .build();
                    
            userAccountRepository.save(account);
            
            log.info("User account created with ID: {} from invite: {}", account.getUserId(), inviteId);
            
            return userMapper.toDto(account);
            
        } catch (ValidationException | ResourceNotFoundException | ResourceExistsException e) {
            log.warn("Failed to accept invite: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while accepting invite: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error accepting invite");
        } catch (Exception e) {
            log.error("Unexpected error while accepting invite: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to process invite acceptance");
        }
    }

    public UserDto updateUser(String shopId, String userId, UpdateUserRequest request) {
        try {
            if (!StringUtils.hasText(shopId)) {
                throw new ValidationException("Shop ID is required");
            }
            if (!StringUtils.hasText(userId)) {
                throw new ValidationException("User ID is required");
            }
            if (request == null) {
                throw new ValidationException("Request body is required");
            }
            
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
            if (!StringUtils.hasText(shopId)) {
                throw new ValidationException("Shop ID is required");
            }
            if (!StringUtils.hasText(userId)) {
                throw new ValidationException("User ID is required");
            }
            
            log.debug("Deactivating user with ID: {} in shop: {}", userId, shopId);
            
            // Find the user and verify they belong to the specified shop
            UserAccount account = userAccountRepository.findById(userId)
                    .filter(user -> shopId.equals(user.getShopId()))
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
            
            // Prevent deactivating the last admin
            if (account.isActive() && "ADMIN".equals(account.getRole())) {
                // Simplified check - in a real app, you'd want to implement a more efficient count query
                long adminCount = userAccountRepository.findByShopId(shopId).stream()
                        .filter(u -> "ADMIN".equals(u.getRole()) && u.isActive())
                        .count();
                
                if (adminCount <= 1) {
                    throw new ValidationException("Cannot deactivate the last admin user in the shop");
                }
            }
            
            if (account.isActive()) {
                account.setActive(false);
                userAccountRepository.save(account);
                log.info("Deactivated user with ID: {} in shop: {}", userId, shopId);
            } else {
                log.debug("User with ID: {} is already deactivated", userId);
            }
            
            return DeactivateUserResponse.builder()
                    .userId(account.getUserId())
                    .active(account.isActive())
                    .build();
                    
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

