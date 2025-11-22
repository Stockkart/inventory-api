package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryRepository extends MongoRepository<Inventory, String> {

  List<Inventory> findByShopId(String shopId);

  List<Inventory> findByShopIdAndProductId(String shopId, String productId);
}

