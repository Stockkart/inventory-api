package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.InvitationStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.InvitationRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.request.SendInvitationRequest;
import com.inventory.user.rest.dto.response.AcceptInvitationResponse;
import com.inventory.user.rest.dto.response.InvitationDto;
import com.inventory.user.rest.dto.response.InvitationListResponse;
import com.inventory.user.rest.dto.response.SendInvitationResponse;
import com.inventory.user.rest.dto.response.ShopUserDto;
import com.inventory.user.rest.dto.response.ShopUserListResponse;
import com.inventory.user.mapper.InvitationMapper;
import com.inventory.user.validation.InvitationValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

  @Autowired
  private UserShopMembershipService membershipService;

  @Autowired
  private UserShopMembershipRepository membershipRepository;

  public SendInvitationResponse sendInvitation(String shopId, String inviterUserId, SendInvitationRequest request) {
    try {
      // Validate request
      invitationValidator.validateSendInvitationRequest(shopId, inviterUserId, request);

      // Verify shop exists using adapter (if available)
      if (shopServiceAdapter != null && !shopServiceAdapter.shopExists(shopId)) {
        throw new ResourceNotFoundException("Shop", "shopId", shopId);
      }

      // Verify inviter exists and has owner access to the shop (multi-shop compatible)
      UserAccount inviter = userAccountRepository.findById(inviterUserId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", inviterUserId));

      if (!membershipService.hasOwnerAccess(inviterUserId, shopId)) {
        throw new ValidationException("Only shop owners can send invitations");
      }

      // Find invitee by email
      String inviteeEmail = request.getInviteeEmail().toLowerCase().trim();
      UserAccount invitee = userAccountRepository.findByEmail(inviteeEmail)
          .orElseThrow(() -> new ResourceNotFoundException("User", "email", inviteeEmail));

      // Check if user is already a member of this shop (membership or legacy shopId)
      if (membershipService.hasAccess(invitee.getUserId(), shopId)) {
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

      invitationValidator.validateInvitationForCurrentUser(invitation, userId);
      invitationValidator.validateInvitationPending(invitation);

      if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(Instant.now())) {
        invitation.setStatus(InvitationStatus.EXPIRED.name());
        invitationRepository.save(invitation);
        invitationValidator.validateInvitationNotExpired(invitation);
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

      // Add membership (multi-shop: does not overwrite existing)
      membershipService.addMembership(userId, invitation.getShopId(), invitation.getRole(),
          UserShopMembershipService.RELATIONSHIP_INVITED);

      // Update invitation status
      invitationMapper.updateInvitationStatus(invitation);
      invitationRepository.save(invitation);

      // Set active shop and role for the accepted shop (so user lands in it)
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
        String shopName = invitation.getShopName();
        if (shopName == null && shopServiceAdapter != null) {
          shopName = shopServiceAdapter.getShopName(invitation.getShopId());
        }
        invitationMapper.enrichInvitationDto(dto, invitation, shopName, inviter, invitee);
        dtos.add(dto);
      }

      return invitationMapper.toInvitationListResponse(dtos);

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

      return invitationMapper.toInvitationListResponse(dtos);

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
      Set<String> addedUserIds = new HashSet<>();

      // Primary: get users from memberships (multi-shop)
      List<UserShopMembership> memberships = membershipRepository.findByShopIdAndActiveTrue(shopId);
      for (UserShopMembership m : memberships) {
        UserAccount user = userAccountRepository.findById(m.getUserId()).orElse(null);
        if (user != null) {
          ShopUserDto dto = invitationMapper.toShopUserDto(user, m.getRelationship(), m.getJoinedAt());
          users.add(dto);
          addedUserIds.add(user.getUserId());
        }
      }

      // Backward compat: add legacy users (shopId on UserAccount, no membership yet)
      List<UserAccount> legacyUsers = userAccountRepository.findByShopId(shopId);
      for (UserAccount user : legacyUsers) {
        if (!addedUserIds.contains(user.getUserId())) {
          Instant joinedAt = user.getUpdatedAt() != null ? user.getUpdatedAt() : user.getCreatedAt();
          ShopUserDto dto = invitationMapper.toShopUserDto(user, UserShopMembershipService.RELATIONSHIP_OWNER, joinedAt);
          users.add(dto);
          addedUserIds.add(user.getUserId());
        }
      }

      // Also add from accepted invitations (invited users who might have different active shopId)
      List<Invitation> acceptedInvitations = invitationRepository.findAcceptedInvitationsByShopId(shopId);
      for (Invitation invitation : acceptedInvitations) {
        if (!addedUserIds.contains(invitation.getInviteeUserId())) {
          UserAccount user = userAccountRepository.findById(invitation.getInviteeUserId()).orElse(null);
          if (user != null) {
            Instant joinedAt = invitation.getAcceptedAt() != null ? invitation.getAcceptedAt() : Instant.EPOCH;
            ShopUserDto dto = invitationMapper.toShopUserDto(user, UserShopMembershipService.RELATIONSHIP_INVITED, joinedAt);
            users.add(dto);
            addedUserIds.add(invitation.getInviteeUserId());
          }
        }
      }

      return invitationMapper.toShopUserListResponse(users);

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

