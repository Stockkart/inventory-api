package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.JoinRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JoinRequestRepository extends MongoRepository<JoinRequest, String> {

  /**
   * Find all join requests for a specific shop
   */
  List<JoinRequest> findByShopId(String shopId);

  /**
   * Find all join requests by a specific user
   */
  List<JoinRequest> findByUserId(String userId);

  /**
   * Find pending join requests for a shop
   */
  List<JoinRequest> findByShopIdAndStatus(String shopId, String status);

  /**
   * Find a pending join request by user and shop
   */
  Optional<JoinRequest> findByUserIdAndShopIdAndStatus(String userId, String shopId, String status);

  /**
   * Check if a pending request exists for user and shop
   */
  boolean existsByUserIdAndShopIdAndStatus(String userId, String shopId, String status);
}

