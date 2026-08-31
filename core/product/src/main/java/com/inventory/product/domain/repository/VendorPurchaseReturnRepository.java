package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.VendorPurchaseReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface VendorPurchaseReturnRepository extends MongoRepository<VendorPurchaseReturn, String> {

  List<VendorPurchaseReturn> findByShopIdAndCreatedAtBetween(String shopId, Instant start, Instant end);

  /** Purchase returns on or after an instant, for rolling live stock counters backwards. */
  @Query("{ 'shopId': ?0, 'createdAt': { '$gte': ?1 } }")
  List<VendorPurchaseReturn> findByShopIdAndCreatedAtFrom(String shopId, Instant startInclusive);

  Page<VendorPurchaseReturn> findByShopId(String shopId, Pageable pageable);

  Page<VendorPurchaseReturn> findByShopIdAndVendorPurchaseInvoiceIdIn(
      String shopId, Collection<String> vendorPurchaseInvoiceIds, Pageable pageable);
}
