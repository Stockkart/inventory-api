package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseRepository extends MongoRepository<Purchase, String> {

  Optional<Purchase> findByUserIdAndShopIdAndStatus(String userId, String shopId, PurchaseStatus status);
  
  Optional<Purchase> findByUserIdAndShopIdAndStatusIn(String userId, String shopId, List<PurchaseStatus> statuses);
  
  Page<Purchase> findByShopId(String shopId, Pageable pageable);
  
  Page<Purchase> findByShopIdAndUserId(String shopId, String userId, Pageable pageable);
}

