package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.InvitationStatus;
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

      // Verify that the inviter is a shop owner (not an invited user)
      // Shop owners are users who don't have an accepted invitation for this shop
      boolean isInvitedUser = invitationRepository
              .findByInviteeUserIdAndShopIdAndStatus(inviterUserId, shopId, InvitationStatus.ACCEPTED.name())
              .isPresent();
      
      if (isInvitedUser) {
        throw new ValidationException("Only shop owners can send invitations");
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
              invitee.getUserId(), shopId, InvitationStatus.PENDING.name())
              .ifPresent(invitation -> {
                throw new ResourceExistsException("A pending invitation already exists for this user");
              });

      log.info("Sending invitation from user {} to user {} for shop {}", 
               inviterUserId, invitee.getUserId(), shopId);

      // Get shop name using adapter (if available)
      String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(shopId) : null;

      // Create invitation
      Invitation invitation = invitationMapper.toEntity(shopId, inviterUserId, request);
      // Ensure timestamps and ID are set (in case @AfterMapping didn't execute)
      invitationMapper.setInvitationTimestampsAndId(invitation, request);
      invitationMapper.setInviteeAndShopName(invitation, invitee.getUserId(), shopName);
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
      if (!InvitationStatus.PENDING.name().equals(invitation.getStatus())) {
        throw new ValidationException("Invitation is not in PENDING status");
      }

      // Check if invitation has expired
      if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(Instant.now())) {
        invitation.setStatus(InvitationStatus.EXPIRED.name());
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

      log.info("Accepting invitation {} for user {} to shop {}", 
               invitationId, userId, invitation.getShopId());

      // Update invitation status and user's shop and role
      invitationMapper.updateInvitationAndUser(invitation, user);
      invitationRepository.save(invitation);
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
        String shopName = invitation.getShopName();
        if (shopName == null && shopServiceAdapter != null) {
          shopName = shopServiceAdapter.getShopName(invitation.getShopId());
        }
        invitationMapper.enrichInvitationDto(dto, invitation, shopName, inviter, invitee);
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

  public InvitationListResponse getInvitationsForShop(String shopId, String userId) {
    try {
      log.debug("Retrieving invitations for shop: {}", shopId);

      List<Invitation> invitations = invitationRepository.findByInviterUserIdAndShopId(userId, shopId);
      
      // Enrich with shop and user details
      List<InvitationDto> dtos = new ArrayList<>();
      String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(shopId) : null;
      
      for (Invitation invitation : invitations) {
        UserAccount inviter = userAccountRepository.findById(invitation.getInviterUserId()).orElse(null);
        UserAccount invitee = userAccountRepository.findById(invitation.getInviteeUserId()).orElse(null);
        
        InvitationDto dto = invitationMapper.toDto(invitation);
        invitationMapper.enrichInvitationDto(dto, invitation, shopName, inviter, invitee);
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
                  .findByInviteeUserIdAndShopIdAndStatus(user.getUserId(), shopId, InvitationStatus.ACCEPTED.name())
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

