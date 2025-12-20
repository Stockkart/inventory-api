package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.ShopVendor;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShopVendorRepository extends MongoRepository<ShopVendor, String> {

  /**
   * Find all shop-vendor relationships for a specific shop.
   *
   * @param shopId the shop ID
   * @return list of shop-vendor relationships
   */
  List<ShopVendor> findByShopId(String shopId);

  /**
   * Find all shop-vendor relationships for a specific vendor.
   *
   * @param vendorId the vendor ID
   * @return list of shop-vendor relationships
   */
  List<ShopVendor> findByVendorId(String vendorId);

  /**
   * Find a specific shop-vendor relationship.
   *
   * @param shopId the shop ID
   * @param vendorId the vendor ID
   * @return an Optional containing the relationship if found, empty otherwise
   */
  Optional<ShopVendor> findByShopIdAndVendorId(String shopId, String vendorId);

  /**
   * Check if a shop-vendor relationship exists.
   *
   * @param shopId the shop ID
   * @param vendorId the vendor ID
   * @return true if the relationship exists, false otherwise
   */
  boolean existsByShopIdAndVendorId(String shopId, String vendorId);
}

