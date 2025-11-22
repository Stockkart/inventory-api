package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Shop;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShopRepository extends MongoRepository<Shop, String> {
    
    /**
     * Find a shop by its business ID.
     *
     * @param businessId the business ID to search for
     * @return an Optional containing the shop if found, empty otherwise
     */
    Optional<Shop> findByBusinessId(String businessId);
    
    /**
     * Find a shop by its initial admin email.
     *
     * @param email the admin email to search for
     * @return an Optional containing the shop if found, empty otherwise
     */
    Optional<Shop> findByInitialAdminEmail(String email);
    
    /**
     * Check if a shop with the given business ID exists.
     *
     * @param businessId the business ID to check
     * @return true if a shop with the given business ID exists, false otherwise
     */
    boolean existsByBusinessId(String businessId);
    
    /**
     * Check if a shop with the given initial admin email exists.
     *
     * @param email the admin email to check
     * @return true if a shop with the given admin email exists, false otherwise
     */
    boolean existsByInitialAdminEmail(String email);
}

