package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ShopCreditEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopCreditEntryRepository extends MongoRepository<ShopCreditEntry, String> {

  List<ShopCreditEntry> findByShopIdOrderByCreatedAtDesc(String shopId, Pageable page);

  boolean existsByReferenceId(String referenceId);

  @Query("{ 'createdAt': { $gte: ?0, $lt: ?1 } }")
  List<ShopCreditEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
}
