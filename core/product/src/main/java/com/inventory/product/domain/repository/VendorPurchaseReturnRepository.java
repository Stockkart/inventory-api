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

  /** Half-open: start counts, end does not. See {@link InventoryRepository}. */
  @Query("{ 'shopId': ?0, 'createdAt': { '$gte': ?1, '$lt': ?2 } }")
  List<VendorPurchaseReturn> findByShopIdAndCreatedAtInPeriod(
      String shopId, Instant startInclusive, Instant endExclusive);

  Page<VendorPurchaseReturn> findByShopId(String shopId, Pageable pageable);

  Page<VendorPurchaseReturn> findByShopIdAndVendorPurchaseInvoiceIdIn(
      String shopId, Collection<String> vendorPurchaseInvoiceIds, Pageable pageable);
}
