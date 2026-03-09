package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.JoinRequest;
import com.inventory.user.domain.model.JoinRequestStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.JoinRequestRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.request.AcceptRejectJoinRequestRequest;
import com.inventory.user.rest.dto.request.SendJoinRequestRequest;
import com.inventory.user.rest.dto.response.JoinRequestDto;
import com.inventory.user.rest.dto.response.JoinRequestListResponse;
import com.inventory.user.rest.dto.response.OwnerShopDto;
import com.inventory.user.rest.dto.response.OwnerShopListResponse;
import com.inventory.user.rest.dto.response.ProcessJoinRequestResponse;
import com.inventory.user.rest.dto.response.SendJoinRequestResponse;
import com.inventory.user.mapper.JoinRequestMapper;
import com.inventory.user.validation.JoinRequestValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class JoinRequestService {

  @Autowired
  private JoinRequestRepository joinRequestRepository;

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired(required = false)
  private ShopServiceAdapter shopServiceAdapter;

  @Autowired
  private JoinRequestMapper joinRequestMapper;

  @Autowired
  private JoinRequestValidator joinRequestValidator;

  @Autowired(required = false)
  private UserShopMembershipService membershipService;

  @Autowired(required = false)
  private UserShopMembershipRepository membershipRepository;

  /**
   * Get shops owned by an email (for join-request flow: user enters owner email and selects a shop).
   */
  @Transactional(readOnly = true)
  public OwnerShopListResponse getShopsByOwnerEmail(String ownerEmail) {
    String email = ownerEmail != null ? ownerEmail.toLowerCase().trim() : null;
    if (email == null || email.isEmpty()) {
      return joinRequestMapper.toOwnerShopListResponse(List.of());
    }
    UserAccount owner = userAccountRepository.findByEmail(email).orElse(null);
    if (owner == null) {
      return joinRequestMapper.toOwnerShopListResponse(List.of());
    }
    if (membershipRepository == null) {
      if (owner.getShopId() != null && !owner.getShopId().trim().isEmpty()) {
        String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(owner.getShopId()) : owner.getShopId();
        return joinRequestMapper.toOwnerShopListResponse(
            List.of(joinRequestMapper.toOwnerShopDto(owner.getShopId(), shopName)));
      }
      return joinRequestMapper.toOwnerShopListResponse(List.of());
    }
    List<UserShopMembership> memberships = membershipRepository.findByUserIdAndActiveTrue(owner.getUserId()).stream()
        .filter(m -> UserShopMembershipService.RELATIONSHIP_OWNER.equals(m.getRelationship()))
        .toList();
    List<OwnerShopDto> shops = memberships.stream()
        .map(m -> {
          String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(m.getShopId()) : m.getShopId();
          return joinRequestMapper.toOwnerShopDto(m.getShopId(), shopName);
        })
        .toList();
    return joinRequestMapper.toOwnerShopListResponse(shops);
  }

  public SendJoinRequestResponse sendJoinRequest(String userId, SendJoinRequestRequest request) {
    try {
      // Validate request
      joinRequestValidator.validateSendJoinRequest(userId, request);

      // Verify user exists
      UserAccount user = userAccountRepository.findById(userId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

      // Find owner by email
      String ownerEmail = request.getOwnerEmail().toLowerCase().trim();
      UserAccount owner = userAccountRepository.findByEmail(ownerEmail)
          .orElseThrow(() -> new ResourceNotFoundException("Owner", "email", ownerEmail));

      // Use shopId from request (multi-shop: requester specifies which shop to join)
      String shopId = request.getShopId().trim();

      boolean ownerHasAccess = membershipService != null
          ? membershipService.hasOwnerAccess(owner.getUserId(), shopId)
          : (owner.getShopId() != null && shopId.equals(owner.getShopId()));
      joinRequestValidator.validateOwnerHasAccessToShop(ownerHasAccess);

      // Check if user already has access to this shop (multi-shop)
      if (membershipService != null && membershipService.hasAccess(userId, shopId)) {
        throw new ResourceExistsException("User already has access to this shop");
      }
      if (membershipService == null && user.getShopId() != null && !user.getShopId().trim().isEmpty()) {
        throw new ResourceExistsException("User already belongs to a shop");
      }

      // Verify shop exists using adapter (if available)
      if (shopServiceAdapter != null && !shopServiceAdapter.shopExists(shopId)) {
        throw new ResourceNotFoundException("Shop", "shopId", shopId);
      }

      // Check if there's already a pending join request for this user and shop
      if (joinRequestRepository.existsByUserIdAndShopIdAndStatus(
          userId, shopId, JoinRequestStatus.PENDING.name())) {
        throw new ResourceExistsException("A pending join request already exists for this shop");
      }

      log.info("Creating join request from user {} to shop {} (owner: {})", userId, shopId, ownerEmail);

      // Get shop name using adapter (if available)
      String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(shopId) : null;

      // Create join request using mapper
      JoinRequest joinRequest = joinRequestMapper.toEntity(request, user);
      // Set shopId and shop name
      joinRequestMapper.setShopId(joinRequest, shopId);
      if (shopName != null) {
        joinRequestMapper.setShopName(joinRequest, shopName);
      }
      joinRequest = joinRequestRepository.save(joinRequest);

      log.info("Created join request with ID: {} for user: {} to shop: {} (owner: {})",
          joinRequest.getRequestId(), userId, shopId, ownerEmail);

      return joinRequestMapper.toResponse(joinRequest);

    } catch (ValidationException | ResourceNotFoundException | ResourceExistsException e) {
      log.warn("Failed to create join request: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while creating join request: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error creating join request");
    } catch (Exception e) {
      log.error("Unexpected error while creating join request: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create join request");
    }
  }

  @Transactional(readOnly = true)
  public JoinRequestListResponse getJoinRequestsForShop(String ownerUserId, String shopIdParam) {
    try {
      log.debug("Retrieving join requests for shop owner: {} shopId: {}", ownerUserId, shopIdParam);

      UserAccount owner = userAccountRepository.findById(ownerUserId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", ownerUserId));

      String shopId;
      if (org.springframework.util.StringUtils.hasText(shopIdParam)) {
        shopId = shopIdParam.trim();
        boolean hasAccess = membershipService != null
            ? membershipService.hasOwnerAccess(ownerUserId, shopId)
            : shopId.equals(owner.getShopId());
        joinRequestValidator.validateOwnerHasAccessToShop(hasAccess);
      } else {
        joinRequestValidator.validateOwnerHasShop(owner.getShopId());
        shopId = owner.getShopId();
      }

      // Get all join requests for this shop
      List<JoinRequest> joinRequests = joinRequestRepository.findByShopId(shopId);

      // Map to DTOs
      List<JoinRequestDto> dtos = joinRequests.stream()
          .map(joinRequestMapper::toDto)
          .collect(Collectors.toList());

      log.debug("Found {} join requests for shop: {}", dtos.size(), shopId);

      return joinRequestMapper.toListResponse(dtos);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Failed to retrieve join requests: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while retrieving join requests: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving join requests");
    } catch (Exception e) {
      log.error("Unexpected error while retrieving join requests: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve join requests");
    }
  }

  public ProcessJoinRequestResponse processJoinRequest(String requestId, String ownerUserId, AcceptRejectJoinRequestRequest request) {
    try {
      // Validate request
      joinRequestValidator.validateAcceptRejectRequest(request);

      log.info("Processing join request {} with action {} by owner {}", requestId, request.getAction(), ownerUserId);

      UserAccount owner = userAccountRepository.findById(ownerUserId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", ownerUserId));

      JoinRequest joinRequest = joinRequestRepository.findById(requestId)
          .orElseThrow(() -> new ResourceNotFoundException("JoinRequest", "requestId", requestId));

      String shopId = joinRequest.getShopId();

      boolean joinRequestBelongsToOwner = membershipService != null
          ? membershipService.hasOwnerAccess(ownerUserId, shopId)
          : shopId.equals(owner.getShopId());
      joinRequestValidator.validateJoinRequestBelongsToOwner(joinRequestBelongsToOwner);
      joinRequestValidator.validateJoinRequestPending(joinRequest.getStatus());

      if (request.getAction() == com.inventory.user.rest.dto.response.JoinRequestAction.ACCEPT) {
        return acceptJoinRequest(joinRequest, ownerUserId, shopId);
      } else {
        return rejectJoinRequest(joinRequest, ownerUserId);
      }

    } catch (ValidationException | ResourceNotFoundException | ResourceExistsException e) {
      log.warn("Failed to process join request: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while processing join request: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing join request");
    } catch (Exception e) {
      log.error("Unexpected error while processing join request: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to process join request");
    }
  }

  private ProcessJoinRequestResponse acceptJoinRequest(JoinRequest joinRequest, String ownerUserId, String shopId) {
    // Find the user who requested to join
    UserAccount user = userAccountRepository.findById(joinRequest.getUserId())
        .orElseThrow(() -> new ResourceNotFoundException("User", "userId", joinRequest.getUserId()));

    // Verify user doesn't already have access to this shop (multi-shop)
    if (membershipService != null && membershipService.hasAccess(user.getUserId(), shopId)) {
      throw new ResourceExistsException("User already has access to this shop");
    }
    if (membershipService == null && user.getShopId() != null && !user.getShopId().trim().isEmpty()) {
      throw new ResourceExistsException("User already belongs to a shop");
    }

    log.info("Accepting join request {} for user {} to shop {}",
        joinRequest.getRequestId(), user.getUserId(), shopId);

    // Add membership (multi-shop)
    if (membershipService != null) {
      UserRole role = UserRole.CASHIER;
      if (joinRequest.getRequestedRole() != null) {
        try {
          role = UserRole.valueOf(joinRequest.getRequestedRole());
        } catch (IllegalArgumentException ignored) {
        }
      }
      membershipService.addMembership(user.getUserId(), shopId, role, UserShopMembershipService.RELATIONSHIP_INVITED);
    }

    // Update join request and user
    joinRequestMapper.updateJoinRequestAndUser(joinRequest, user, ownerUserId);
    joinRequestRepository.save(joinRequest);
    userAccountRepository.save(user);

    log.info("Join request {} accepted. User {} added to shop {}",
        joinRequest.getRequestId(), user.getUserId(), shopId);

    return joinRequestMapper.toProcessResponse(joinRequest, user, JoinRequestStatus.APPROVED.name(), "Join request accepted successfully");
  }

  private ProcessJoinRequestResponse rejectJoinRequest(JoinRequest joinRequest, String ownerUserId) {
    log.info("Rejecting join request {}", joinRequest.getRequestId());

    // Update join request status
    joinRequestMapper.rejectJoinRequest(joinRequest, ownerUserId);
    joinRequestRepository.save(joinRequest);

    log.info("Join request {} rejected", joinRequest.getRequestId());

    // Get user for response
    UserAccount user = userAccountRepository.findById(joinRequest.getUserId())
        .orElse(null);

    return joinRequestMapper.toProcessResponse(joinRequest, user, JoinRequestStatus.REJECTED.name(), "Join request rejected");
  }
}

