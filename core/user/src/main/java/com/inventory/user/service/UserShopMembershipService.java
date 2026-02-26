package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.InvitationStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.InvitationRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.invitation.UserShopDto;
import com.inventory.user.rest.dto.invitation.UserShopListResponse;
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
public class UserShopMembershipService {

  public static final String RELATIONSHIP_OWNER = "OWNER";
  public static final String RELATIONSHIP_INVITED = "INVITED";

  @Autowired
  private UserShopMembershipRepository membershipRepository;

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired(required = false)
  private ShopServiceAdapter shopServiceAdapter;

  @Autowired(required = false)
  private InvitationRepository invitationRepository;

  /**
   * Check if user has access to a shop. Backward compatible: returns true if user has
   * membership OR if user.shopId matches (legacy users not yet in memberships).
   */
  @Transactional(readOnly = true)
  public boolean hasAccess(String userId, String shopId) {
    if (userId == null || shopId == null) {
      return false;
    }
    if (membershipRepository.existsByUserIdAndShopIdAndActiveTrue(userId, shopId)) {
      return true;
    }
    // Backward compatibility: check UserAccount.shopId
    return userAccountRepository.findById(userId)
        .map(u -> shopId.equals(u.getShopId()))
        .orElse(false);
  }

  /**
   * Check if user has owner access (can send invitations, manage users) for a shop.
   * Owner = has membership with relationship OWNER, or legacy: user.shopId equals shopId
   * and user is not an invited user (no accepted invitation for this shop - we infer from membership).
   */
  @Transactional(readOnly = true)
  public boolean hasOwnerAccess(String userId, String shopId) {
    if (userId == null || shopId == null) {
      return false;
    }
    return membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
        .map(m -> RELATIONSHIP_OWNER.equals(m.getRelationship()))
        .orElseGet(() -> {
          // Legacy: user.shopId equals shopId implies owner (pre-membership behavior)
          return userAccountRepository.findById(userId)
              .map(u -> shopId.equals(u.getShopId()))
              .orElse(false);
        });
  }

  /**
   * Get all shops the user has access to. Combines memberships with legacy shopId.
   */
  @Transactional(readOnly = true)
  public UserShopListResponse getShopsForUser(String userId) {
    try {
      List<UserShopMembership> memberships = membershipRepository.findByUserIdAndActiveTrue(userId);
      List<UserShopDto> shops = new ArrayList<>();

      for (UserShopMembership m : memberships) {
        String shopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(m.getShopId()) : null;
        shops.add(new UserShopDto(
            m.getShopId(),
            shopName != null ? shopName : m.getShopId(),
            m.getRole().name(),
            m.getRelationship(),
            m.getJoinedAt()
        ));
      }

      // Backward compatibility: if user has shopId but no membership for it, include it
      userAccountRepository.findById(userId).ifPresent(account -> {
        if (account.getShopId() != null && !account.getShopId().trim().isEmpty()) {
          boolean alreadyInList = shops.stream()
              .anyMatch(s -> account.getShopId().equals(s.getShopId()));
          if (!alreadyInList) {
            String shopName = shopServiceAdapter != null
                ? shopServiceAdapter.getShopName(account.getShopId())
                : account.getShopId();
            shops.add(new UserShopDto(
                account.getShopId(),
                shopName != null ? shopName : account.getShopId(),
                account.getRole() != null ? account.getRole().name() : UserRole.OWNER.name(),
                RELATIONSHIP_OWNER,
                account.getUpdatedAt() != null ? account.getUpdatedAt() : account.getCreatedAt()
            ));
          }
        }
      });

      return new UserShopListResponse(shops);
    } catch (DataAccessException e) {
      log.error("Database error while getting shops for user {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving user shops");
    }
  }

  /**
   * Add a membership for a user to a shop. Used when registering a shop or accepting an invitation.
   */
  public UserShopMembership addMembership(String userId, String shopId, UserRole role, String relationship) {
    if (membershipRepository.existsByUserIdAndShopIdAndActiveTrue(userId, shopId)) {
      log.debug("User {} already has active membership for shop {}", userId, shopId);
      return membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId).orElseThrow();
    }
    UserShopMembership membership = new UserShopMembership();
    membership.setUserId(userId);
    membership.setShopId(shopId);
    membership.setRole(role != null ? role : UserRole.OWNER);
    membership.setRelationship(relationship != null ? relationship : RELATIONSHIP_OWNER);
    membership.setActive(true);
    membership.setJoinedAt(Instant.now());
    return membershipRepository.save(membership);
  }

  /**
   * Switch the user's active shop. Updates UserAccount.shopId. Validates user has access.
   */
  public void switchActiveShop(String userId, String shopId) {
    if (!hasAccess(userId, shopId)) {
      throw new ValidationException("User does not have access to this shop");
    }
    UserAccount account = userAccountRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    account.setShopId(shopId);
    account.setUpdatedAt(Instant.now());
    // Sync role from membership if available
    membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
        .ifPresent(m -> account.setRole(m.getRole()));
    userAccountRepository.save(account);
    log.info("User {} switched active shop to {}", userId, shopId);
  }

  /**
   * Ensure membership exists for legacy user (shopId set on UserAccount). Used during migration.
   * If user has accepted invitation for this shop, creates INVITED membership; otherwise OWNER.
   */
  public void ensureMembershipForLegacyUser(UserAccount account) {
    if (account.getShopId() == null || account.getShopId().trim().isEmpty()) {
      return;
    }
    if (membershipRepository.existsByUserIdAndShopIdAndActiveTrue(account.getUserId(), account.getShopId())) {
      return;
    }
    String relationship = RELATIONSHIP_OWNER;
    UserRole role = account.getRole() != null ? account.getRole() : UserRole.OWNER;
    if (invitationRepository != null) {
      var invitation = invitationRepository.findByInviteeUserIdAndShopIdAndStatus(
          account.getUserId(), account.getShopId(), InvitationStatus.ACCEPTED.name());
      if (invitation.isPresent()) {
        relationship = RELATIONSHIP_INVITED;
        role = invitation.get().getRole() != null ? invitation.get().getRole() : role;
      }
    }
    addMembership(account.getUserId(), account.getShopId(), role, relationship);
    log.debug("Created membership for legacy user {} on shop {} (relationship={})",
        account.getUserId(), account.getShopId(), relationship);
  }
}
