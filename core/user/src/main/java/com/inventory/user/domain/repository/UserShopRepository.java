package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.UserShop;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserShopRepository extends MongoRepository<UserShop, String> {

  /**
   * Find all shop relationships for a specific user.
   *
   * @param userId the user ID
   * @return list of user-shop relationships
   */
  List<UserShop> findByUserId(String userId);

  /**
   * Find all user relationships for a specific shop.
   *
   * @param shopId the shop ID
   * @return list of user-shop relationships
   */
  List<UserShop> findByShopId(String shopId);

  /**
   * Find active shop relationships for a user.
   *
   * @param userId the user ID
   * @return list of active user-shop relationships
   */
  List<UserShop> findByUserIdAndActiveTrue(String userId);

  /**
   * Find active user relationships for a shop.
   *
   * @param shopId the shop ID
   * @return list of active user-shop relationships
   */
  List<UserShop> findByShopIdAndActiveTrue(String shopId);

  /**
   * Find a specific user-shop relationship.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   * @return optional user-shop relationship
   */
  Optional<UserShop> findByUserIdAndShopId(String userId, String shopId);

  /**
   * Find active user-shop relationship.
   *
   * @param userId the user ID
   * @param shopId the shop ID
   * @return optional active user-shop relationship
   */
  Optional<UserShop> findByUserIdAndShopIdAndActiveTrue(String userId, String shopId);

  /**
   * Find primary shop for a user.
   *
   * @param userId the user ID
   * @return optional primary user-shop relationship
   */
  Optional<UserShop> findByUserIdAndPrimaryTrue(String userId);

  /**
   * Find users by shop and role.
   *
   * @param shopId the shop ID
   * @param role the role
   * @return list of user-shop relationships
   */
  List<UserShop> findByShopIdAndRoleAndActiveTrue(String shopId, String role);

  /**
   * Find shops by user and role.
   *
   * @param userId the user ID
   * @param role the role
   * @return list of user-shop relationships
   */
  List<UserShop> findByUserIdAndRoleAndActiveTrue(String userId, String role);

  /**
   * Count active users in a shop.
   *
   * @param shopId the shop ID
   * @return count of active users
   */
  long countByShopIdAndActiveTrue(String shopId);
}

