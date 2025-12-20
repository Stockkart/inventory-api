package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.ShopCustomer;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShopCustomerRepository extends MongoRepository<ShopCustomer, String> {

  /**
   * Find all shop-customer relationships for a specific shop.
   *
   * @param shopId the shop ID
   * @return list of shop-customer relationships
   */
  List<ShopCustomer> findByShopId(String shopId);

  /**
   * Find all shop-customer relationships for a specific customer.
   *
   * @param customerId the customer ID
   * @return list of shop-customer relationships
   */
  List<ShopCustomer> findByCustomerId(String customerId);

  /**
   * Find a specific shop-customer relationship.
   *
   * @param shopId the shop ID
   * @param customerId the customer ID
   * @return an Optional containing the relationship if found, empty otherwise
   */
  Optional<ShopCustomer> findByShopIdAndCustomerId(String shopId, String customerId);

  /**
   * Check if a shop-customer relationship exists.
   *
   * @param shopId the shop ID
   * @param customerId the customer ID
   * @return true if the relationship exists, false otherwise
   */
  boolean existsByShopIdAndCustomerId(String shopId, String customerId);
}

