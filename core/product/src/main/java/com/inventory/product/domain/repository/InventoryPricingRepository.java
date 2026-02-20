package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface InventoryPricingRepository {

  List<Inventory> findByShopId(String shopId, Pageable pageable);

  List<Inventory> searchByShopIdAndQuery(String shopId, String query);
}
