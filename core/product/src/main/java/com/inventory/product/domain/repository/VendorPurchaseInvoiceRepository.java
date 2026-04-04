package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface VendorPurchaseInvoiceRepository extends MongoRepository<VendorPurchaseInvoice, String> {

  boolean existsByShopIdAndVendorIdAndInvoiceNo(String shopId, String vendorId, String invoiceNo);

  /** Newest first: pass {@link org.springframework.data.domain.Pageable} with sort {@code createdAt} DESC, then {@code id} DESC. */
  Page<VendorPurchaseInvoice> findByShopId(String shopId, Pageable pageable);

  Optional<VendorPurchaseInvoice> findByIdAndShopId(String id, String shopId);
}
