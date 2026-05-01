package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import java.util.List;

public interface VendorPurchaseInvoiceRepository extends MongoRepository<VendorPurchaseInvoice, String> {

  boolean existsByShopIdAndVendorIdAndInvoiceNo(String shopId, String vendorId, String invoiceNo);

  /** Newest first: pass {@link org.springframework.data.domain.Pageable} with sort {@code createdAt} DESC, then {@code id} DESC. */
  Page<VendorPurchaseInvoice> findByShopId(String shopId, Pageable pageable);
  List<VendorPurchaseInvoice> findByShopId(String shopId);

  Optional<VendorPurchaseInvoice> findByIdAndShopId(String id, String shopId);
}
