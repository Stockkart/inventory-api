package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

public interface VendorPurchaseInvoiceRepository extends MongoRepository<VendorPurchaseInvoice, String> {

  boolean existsByShopIdAndVendorIdAndInvoiceNo(String shopId, String vendorId, String invoiceNo);

  /** Newest first: pass {@link org.springframework.data.domain.Pageable} with sort {@code createdAt} DESC, then {@code id} DESC. */
  Page<VendorPurchaseInvoice> findByShopId(String shopId, Pageable pageable);
  List<VendorPurchaseInvoice> findByShopId(String shopId);

  /** Half-open: start counts, end does not. See {@link InventoryRepository}. */
  @Query("{ 'shopId': ?0, 'invoiceDate': { '$gte': ?1, '$lt': ?2 } }")
  List<VendorPurchaseInvoice> findByShopIdAndInvoiceDateInPeriod(
      String shopId, Instant startInclusive, Instant endExclusive);

  Optional<VendorPurchaseInvoice> findByIdAndShopId(String id, String shopId);

  List<VendorPurchaseInvoice> findByShopIdAndInvoiceNo(String shopId, String invoiceNo);
}
