package com.inventory.product.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Read-only product queries for MIS / reporting consumers in other modules. Prefer this over
 * reaching into product repositories from outside {@code core/product}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MisProductQueryService {

  private final VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;
  private final VendorPurchaseReturnRepository vendorPurchaseReturnRepository;
  private final PurchaseRepository purchaseRepository;
  private final RefundRepository refundRepository;
  private final InventoryRepository inventoryRepository;
  private final VendorRepository vendorRepository;
  private final CustomerRepository customerRepository;

  public List<VendorPurchaseInvoice> findVendorInvoicesByInvoiceDate(
      String shopId, Instant fromInclusive, Instant toExclusive) {
    return vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceDateInPeriod(
        shopId, fromInclusive, toExclusive);
  }

  public List<VendorPurchaseInvoice> findAllVendorInvoices(String shopId) {
    return vendorPurchaseInvoiceRepository.findByShopId(shopId);
  }

  public Optional<VendorPurchaseInvoice> findVendorInvoiceById(String shopId, String id) {
    return vendorPurchaseInvoiceRepository.findByIdAndShopId(id, shopId);
  }

  public List<VendorPurchaseReturn> findVendorReturnsByCreatedAt(
      String shopId, Instant fromInclusive, Instant toExclusive) {
    return vendorPurchaseReturnRepository.findByShopIdAndCreatedAtInPeriod(
        shopId, fromInclusive, toExclusive);
  }

  public List<Purchase> findCompletedSalesBySoldAt(
      String shopId, Instant fromInclusive, Instant toExclusive) {
    return purchaseRepository.findByShopIdAndStatusAndSoldAtInPeriod(
        shopId, PurchaseStatus.COMPLETED, fromInclusive, toExclusive);
  }

  public List<Refund> findRefundsByCreatedAt(
      String shopId, Instant fromInclusive, Instant toExclusive) {
    return refundRepository.findByShopIdAndCreatedAtInPeriod(
        shopId, fromInclusive, toExclusive);
  }

  public List<Inventory> findAllInventory(String shopId) {
    return inventoryRepository.findByShopId(shopId);
  }

  public Map<String, String> resolveVendorNames(Collection<String> vendorIds) {
    Map<String, String> out = new HashMap<>();
    if (vendorIds == null || vendorIds.isEmpty()) {
      return out;
    }
    vendorRepository
        .findAllById(vendorIds.stream().filter(StringUtils::hasText).map(String::trim).toList())
        .forEach(
            (Vendor v) -> {
              if (v.getId() != null && StringUtils.hasText(v.getName())) {
                out.put(v.getId(), v.getName().trim());
              }
            });
    return out;
  }

  public Map<String, String> resolveCustomerNames(Collection<String> customerIds) {
    Map<String, String> out = new HashMap<>();
    if (customerIds == null || customerIds.isEmpty()) {
      return out;
    }
    customerRepository
        .findAllById(customerIds.stream().filter(StringUtils::hasText).map(String::trim).toList())
        .forEach(
            (Customer c) -> {
              if (c.getId() != null && StringUtils.hasText(c.getName())) {
                out.put(c.getId(), c.getName().trim());
              }
            });
    return out;
  }
}
