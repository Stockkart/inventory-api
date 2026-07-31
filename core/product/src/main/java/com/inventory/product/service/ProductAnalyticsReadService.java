package com.inventory.product.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to inventory and purchase history for reporting modules.
 *
 * <p>Analytics previously autowired {@code InventoryRepository} and {@code PurchaseRepository}
 * directly, so this module's persistence was a compile-time dependency of another module's
 * reporting code. Routing through a service keeps that knowledge here and gives one place to add
 * projections or paging if these full-collection reads become a problem.
 */
@Service
@RequiredArgsConstructor
public class ProductAnalyticsReadService {

  private final InventoryRepository inventoryRepository;
  private final PurchaseRepository purchaseRepository;

  @Transactional(readOnly = true)
  public Optional<Inventory> findInventoryById(String inventoryId) {
    return inventoryRepository.findById(inventoryId);
  }

  /** Every inventory row for a shop. Callers aggregate in memory. */
  @Transactional(readOnly = true)
  public List<Inventory> findInventoryForShop(String shopId) {
    return inventoryRepository.findByShopId(shopId);
  }

  /**
   * Every purchase for a shop. Callers aggregate in memory.
   *
   * <p>The repository only exposes a paged finder, so the unpaged request is made here rather than
   * leaving every caller to remember {@code Pageable.unpaged()}.
   */
  @Transactional(readOnly = true)
  public List<Purchase> findPurchasesForShop(String shopId) {
    return purchaseRepository.findByShopId(shopId, Pageable.unpaged()).getContent();
  }
}
