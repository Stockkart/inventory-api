package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.repository.InvitationRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.invitation.*;
import com.inventory.user.rest.mapper.InvitationMapper;
import com.inventory.user.validation.InvitationValidator;
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
public class InvitationService {

  @Autowired
  private InvitationRepository invitationRepository;

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired(required = false)
  private ShopServiceAdapter shopServiceAdapter;

  @Autowired
  private InvitationMapper invitationMapper;

  @Autowired
  private InvitationValidator invitationValidator;

  public SendInvitationResponse sendInvitation(String shopId, String inviterUserId, SendInvitationRequest request) {
    try {
      // Validate request
      invitationValidator.validateSendInvitationRequest(shopId, inviterUserId, request);

      // Verify shop exists using adapter (if available)
      if (shopServiceAdapter != null && !shopServiceAdapter.shopExists(shopId)) {
        throw new ResourceNotFoundException("Shop", "shopId", shopId);
      }

      // Verify inviter exists and belongs to the shop
      UserAccount inviter = userAccountRepository.findById(inviterUserId)
              .orElseThrow(() -> new ResourceNotFoundException("User", "userId", inviterUserId));

      if (!shopId.equals(inviter.getShopId())) {
        throw new ValidationException("User does not belong to this shop");
      }

      // Find invitee by email
      String inviteeEmail = request.getInviteeEmail().toLowerCase().trim();
      UserAccount invitee = userAccountRepository.findByEmail(inviteeEmail)
              .orElseThrow(() -> new ResourceNotFoundException("User", "email", inviteeEmail));

      // Check if user is already a member of this shop
      if (shopId.equals(invitee.getShopId())) {
        throw new ResourceExistsException("User is already a member of this shop");
      }

      // Check if there's already a pending invitation for this user and shop
      invitationRepository.findByInviteeUserIdAndShopIdAndStatus(
              invitee.getUserId(), shopId, "PENDING")
              .ifPresent(invitation -> {
                throw new ResourceExistsException("A pending invitation already exists for this user");
              });

      log.info("Sending invitation from user {} to user {} for shop {}", 
               inviterUserId, invitee.getUserId(), shopId);

      // Get shop name using adapter (if available)
      String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(shopId) : null;

      // Create invitation
      Invitation invitation = invitationMapper.toEntity(shopId, inviterUserId, request);
      invitation.setInviteeUserId(invitee.getUserId());
      invitation.setShopName(shopName);
      invitation = invitationRepository.save(invitation);

      log.info("Created invitation with ID: {} for user: {} to shop: {}", 
               invitation.getInvitationId(), invitee.getUserId(), shopId);

      return invitationMapper.toSendResponse(invitation);

    } catch (ValidationException | ResourceNotFoundException | ResourceExistsException e) {
      log.warn("Failed to send invitation: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while sending invitation: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error sending invitation");
    } catch (Exception e) {
      log.error("Unexpected error while sending invitation: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to send invitation");
    }
  }

  public AcceptInvitationResponse acceptInvitation(String invitationId, String userId) {
    try {
      // Validate request
      invitationValidator.validateAcceptInvitationRequest(invitationId);

      // Find invitation
      Invitation invitation = invitationRepository.findById(invitationId)
              .orElseThrow(() -> new ResourceNotFoundException("Invitation", "invitationId", invitationId));

      // Verify invitation is for this user
      if (!userId.equals(invitation.getInviteeUserId())) {
        throw new ValidationException("This invitation is not for the current user");
      }

      // Verify invitation status
      if (!"PENDING".equals(invitation.getStatus())) {
        throw new ValidationException("Invitation is not in PENDING status");
      }

      // Check if invitation has expired
      if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(Instant.now())) {
        invitation.setStatus("EXPIRED");
        invitationRepository.save(invitation);
        throw new ValidationException("Invitation has expired");
      }

      // Verify shop exists using adapter (if available)
      if (shopServiceAdapter != null && !shopServiceAdapter.shopExists(invitation.getShopId())) {
        throw new ResourceNotFoundException("Shop", "shopId", invitation.getShopId());
      }

      // Find user
      UserAccount user = userAccountRepository.findById(userId)
              .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

      // Check if user already belongs to another shop
      if (user.getShopId() != null && !user.getShopId().trim().isEmpty() && !user.getShopId().equals(invitation.getShopId())) {
        throw new ResourceExistsException("User already belongs to another shop");
      }

      log.info("Accepting invitation {} for user {} to shop {}", 
               invitationId, userId, invitation.getShopId());

      // Update invitation status
      invitation.setStatus("ACCEPTED");
      invitation.setAcceptedAt(Instant.now());
      invitationRepository.save(invitation);

      // Update user's shop and role
      user.setShopId(invitation.getShopId());
      user.setRole(invitation.getRole());
      user.setUpdatedAt(Instant.now());
      userAccountRepository.save(user);

      log.info("User {} accepted invitation {} and joined shop {}", 
               userId, invitationId, invitation.getShopId());

      // Get shop name from invitation (stored when invitation was created)
      String shopName = invitation.getShopName();
      if (shopName == null && shopServiceAdapter != null) {
        shopName = shopServiceAdapter.getShopName(invitation.getShopId());
      }

      return invitationMapper.toAcceptResponse(invitation, shopName, user);

    } catch (ValidationException | ResourceNotFoundException | ResourceExistsException e) {
      log.warn("Failed to accept invitation: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while accepting invitation: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error accepting invitation");
    } catch (Exception e) {
      log.error("Unexpected error while accepting invitation: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to accept invitation");
    }
  }

  public InvitationListResponse getInvitationsForUser(String userId) {
    try {
      log.debug("Retrieving invitations for user: {}", userId);

      List<Invitation> invitations = invitationRepository.findByInviteeUserId(userId);
      
      // Enrich with shop and user details
      List<InvitationDto> dtos = new ArrayList<>();
      for (Invitation invitation : invitations) {
        UserAccount inviter = userAccountRepository.findById(invitation.getInviterUserId()).orElse(null);
        UserAccount invitee = userAccountRepository.findById(invitation.getInviteeUserId()).orElse(null);
        
        InvitationDto dto = invitationMapper.toDto(invitation);
        // Use shop name from invitation, or fetch if not available
        if (invitation.getShopName() != null) {
          dto.setShopName(invitation.getShopName());
        } else if (shopServiceAdapter != null) {
          dto.setShopName(shopServiceAdapter.getShopName(invitation.getShopId()));
        }
        if (inviter != null) {
          dto.setInviterName(inviter.getName());
        }
        if (invitee != null) {
          dto.setInviteeName(invitee.getName());
        }
        dtos.add(dto);
      }

      return InvitationListResponse.builder()
              .data(dtos)
              .build();

    } catch (DataAccessException e) {
      log.error("Database error while retrieving invitations: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving invitations");
    } catch (Exception e) {
      log.error("Unexpected error while retrieving invitations: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve invitations");
    }
  }

  public InvitationListResponse getInvitationsForShop(String shopId) {
    try {
      log.debug("Retrieving invitations for shop: {}", shopId);

      List<Invitation> invitations = invitationRepository.findByShopId(shopId);
      
      // Enrich with shop and user details
      List<InvitationDto> dtos = new ArrayList<>();
      String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(shopId) : null;
      
      for (Invitation invitation : invitations) {
        UserAccount inviter = userAccountRepository.findById(invitation.getInviterUserId()).orElse(null);
        UserAccount invitee = userAccountRepository.findById(invitation.getInviteeUserId()).orElse(null);
        
        InvitationDto dto = invitationMapper.toDto(invitation);
        // Use shop name from invitation, or fetched name, or fallback
        if (invitation.getShopName() != null) {
          dto.setShopName(invitation.getShopName());
        } else if (shopName != null) {
          dto.setShopName(shopName);
        }
        if (inviter != null) {
          dto.setInviterName(inviter.getName());
        }
        if (invitee != null) {
          dto.setInviteeName(invitee.getName());
        }
        dtos.add(dto);
      }

      return InvitationListResponse.builder()
              .data(dtos)
              .build();

    } catch (DataAccessException e) {
      log.error("Database error while retrieving invitations: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving invitations");
    } catch (Exception e) {
      log.error("Unexpected error while retrieving invitations: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve invitations");
    }
  }

  @Transactional(readOnly = true)
  public UserShopListResponse getShopsForUser(String userId) {
    try {
      log.debug("Retrieving shops for user: {}", userId);

      UserAccount user = userAccountRepository.findById(userId)
              .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

      List<UserShopDto> shops = new ArrayList<>();

      // Get owned shop (if user has shopId set)
      if (user.getShopId() != null && !user.getShopId().trim().isEmpty()) {
        String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(user.getShopId()) : null;
        if (shopName != null || shopServiceAdapter == null) {
          // Create a simple DTO with shop info
          UserShopDto dto = UserShopDto.builder()
                  .shopId(user.getShopId())
                  .shopName(shopName)
                  .role(user.getRole())
                  .relationship("OWNER")
                  .joinedAt(user.getUpdatedAt())
                  .build();
          shops.add(dto);
        }
      }

      // Get invited shops (accepted invitations)
      List<Invitation> acceptedInvitations = invitationRepository.findAcceptedInvitationsByUserId(userId);
      for (Invitation invitation : acceptedInvitations) {
        // Skip if this is the owned shop (already added)
        if (!invitation.getShopId().equals(user.getShopId())) {
          String shopName = invitation.getShopName();
          if (shopName == null && shopServiceAdapter != null) {
            shopName = shopServiceAdapter.getShopName(invitation.getShopId());
          }
          UserShopDto dto = UserShopDto.builder()
                  .shopId(invitation.getShopId())
                  .shopName(shopName)
                  .role(invitation.getRole())
                  .relationship("INVITED")
                  .joinedAt(invitation.getAcceptedAt())
                  .build();
          shops.add(dto);
        }
      }

      return UserShopListResponse.builder()
              .data(shops)
              .build();

    } catch (ResourceNotFoundException e) {
      log.warn("Failed to get shops for user: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while retrieving shops for user: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving shops");
    } catch (Exception e) {
      log.error("Unexpected error while retrieving shops for user: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve shops");
    }
  }

  @Transactional(readOnly = true)
  public ShopUserListResponse getUsersForShop(String shopId) {
    try {
      log.debug("Retrieving users for shop: {}", shopId);

      // Verify shop exists using adapter (if available)
      if (shopServiceAdapter != null && !shopServiceAdapter.shopExists(shopId)) {
        throw new ResourceNotFoundException("Shop", "shopId", shopId);
      }

      List<ShopUserDto> users = new ArrayList<>();

      // Get owner users (users with shopId matching this shop)
      List<UserAccount> ownerUsers = userAccountRepository.findByShopId(shopId);
      for (UserAccount user : ownerUsers) {
        // Check if this user is the owner (has shopId set) or was invited
        boolean isOwner = shopId.equals(user.getShopId());
        String relationship = isOwner ? "OWNER" : "INVITED";
        
        // Get joined date - for owners, use updatedAt; for invited, get from invitation
        Instant joinedAt = user.getUpdatedAt();
        if (!isOwner) {
          Invitation invitation = invitationRepository
                  .findByInviteeUserIdAndShopIdAndStatus(user.getUserId(), shopId, "ACCEPTED")
                  .orElse(null);
          if (invitation != null && invitation.getAcceptedAt() != null) {
            joinedAt = invitation.getAcceptedAt();
          }
        }

        ShopUserDto dto = invitationMapper.toShopUserDto(user, relationship, joinedAt);
        users.add(dto);
      }

      // Get invited users (accepted invitations) that might not be in the owner list
      List<Invitation> acceptedInvitations = invitationRepository.findAcceptedInvitationsByShopId(shopId);
      for (Invitation invitation : acceptedInvitations) {
        // Check if user is already in the list
        boolean alreadyAdded = users.stream()
                .anyMatch(u -> u.getUserId().equals(invitation.getInviteeUserId()));
        
        if (!alreadyAdded) {
          UserAccount user = userAccountRepository.findById(invitation.getInviteeUserId()).orElse(null);
          if (user != null) {
            ShopUserDto dto = invitationMapper.toShopUserDto(
                    user, "INVITED", invitation.getAcceptedAt());
            users.add(dto);
          }
        }
      }

      return ShopUserListResponse.builder()
              .data(users)
              .build();

    } catch (ResourceNotFoundException e) {
      log.warn("Failed to get users for shop: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while retrieving users for shop: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving users");
    } catch (Exception e) {
      log.error("Unexpected error while retrieving users for shop: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve users");
    }
  }
}

