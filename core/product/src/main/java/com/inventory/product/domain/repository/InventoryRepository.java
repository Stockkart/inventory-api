package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface InventoryRepository extends MongoRepository<Inventory, String> {

    List<Inventory> findByShopId(String shopId);
}

