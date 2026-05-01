package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.VendorPurchaseReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface VendorPurchaseReturnRepository extends MongoRepository<VendorPurchaseReturn, String> {

  List<VendorPurchaseReturn> findByShopIdAndCreatedAtBetween(String shopId, Instant start, Instant end);

  Page<VendorPurchaseReturn> findByShopId(String shopId, Pageable pageable);

  Page<VendorPurchaseReturn> findByShopIdAndVendorPurchaseInvoiceIdIn(
      String shopId, Collection<String> vendorPurchaseInvoiceIds, Pageable pageable);
}
