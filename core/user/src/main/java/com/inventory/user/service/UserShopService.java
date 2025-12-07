package com.inventory.user.service;

import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.user.domain.model.UserShop;
import com.inventory.user.domain.repository.UserShopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class UserShopService {

  @Autowired
  private UserShopRepository userShopRepository;

  /**
   * Create a user-shop relationship.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   * @param role the role (ADMIN, MANAGER, CASHIER)
   * @param relationship the relationship type (OWNER, INVITED)
   * @param isPrimary whether this is the primary shop for the user
   * @return the created UserShop relationship
   */
  public UserShop createUserShopRelationship(String userId, String shopId, String role, 
                                              String relationship, boolean isPrimary) {
    // Check if relationship already exists
    userShopRepository.findByUserIdAndShopId(userId, shopId)
            .ifPresent(existing -> {
              throw new ResourceExistsException("User is already associated with this shop");
            });

    // If this is set as primary, unset other primary shops for this user
    if (isPrimary) {
      userShopRepository.findByUserIdAndPrimaryTrue(userId)
              .ifPresent(primary -> {
                primary.setPrimary(false);
                primary.setUpdatedAt(Instant.now());
                userShopRepository.save(primary);
              });
    }

    UserShop userShop = UserShop.builder()
            .userShopId("user-shop-" + UUID.randomUUID())
            .userId(userId)
            .shopId(shopId)
            .role(role)
            .relationship(relationship)
            .active(true)
            .primary(isPrimary)
            .joinedAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    return userShopRepository.save(userShop);
  }

  /**
   * Check if user belongs to a shop.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   * @return true if user belongs to the shop, false otherwise
   */
  @Transactional(readOnly = true)
  public boolean userBelongsToShop(String userId, String shopId) {
    return userShopRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId).isPresent();
  }

  /**
   * Get user's role in a specific shop.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   * @return the role, or null if user doesn't belong to the shop
   */
  @Transactional(readOnly = true)
  public String getUserRoleInShop(String userId, String shopId) {
    return userShopRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
            .map(UserShop::getRole)
            .orElse(null);
  }

  /**
   * Get primary shop for a user.
   *
   * @param userId the user ID
   * @return the primary shop ID, or null if no primary shop is set
   */
  @Transactional(readOnly = true)
  public String getPrimaryShopId(String userId) {
    return userShopRepository.findByUserIdAndPrimaryTrue(userId)
            .map(UserShop::getShopId)
            .orElse(null);
  }

  /**
   * Get all shops for a user.
   *
   * @param userId the user ID
   * @return list of user-shop relationships
   */
  @Transactional(readOnly = true)
  public List<UserShop> getShopsForUser(String userId) {
    return userShopRepository.findByUserIdAndActiveTrue(userId);
  }

  /**
   * Get all users for a shop.
   *
   * @param shopId the shop ID
   * @return list of user-shop relationships
   */
  @Transactional(readOnly = true)
  public List<UserShop> getUsersForShop(String shopId) {
    return userShopRepository.findByShopIdAndActiveTrue(shopId);
  }

  /**
   * Set a shop as primary for a user.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   */
  public void setPrimaryShop(String userId, String shopId) {
    UserShop userShop = userShopRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("UserShop", "userId and shopId", userId + "-" + shopId));

    // Unset other primary shops
    userShopRepository.findByUserIdAndPrimaryTrue(userId)
            .ifPresent(primary -> {
              if (!primary.getUserShopId().equals(userShop.getUserShopId())) {
                primary.setPrimary(false);
                primary.setUpdatedAt(Instant.now());
                userShopRepository.save(primary);
              }
            });

    userShop.setPrimary(true);
    userShop.setUpdatedAt(Instant.now());
    userShopRepository.save(userShop);
  }

  /**
   * Update user's role in a shop.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   * @param role the new role
   */
  public void updateUserRoleInShop(String userId, String shopId, String role) {
    UserShop userShop = userShopRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("UserShop", "userId and shopId", userId + "-" + shopId));

    userShop.setRole(role);
    userShop.setUpdatedAt(Instant.now());
    userShopRepository.save(userShop);
  }

  /**
   * Deactivate a user-shop relationship.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   */
  public void deactivateUserShopRelationship(String userId, String shopId) {
    UserShop userShop = userShopRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("UserShop", "userId and shopId", userId + "-" + shopId));

    userShop.setActive(false);
    userShop.setUpdatedAt(Instant.now());
    userShopRepository.save(userShop);
  }
}

