package com.inventory.plugins.grocery.domain.repository;

import com.inventory.plugins.grocery.domain.model.GroceryInventoryExtension;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GroceryExtensionMongoRepository
    extends MongoRepository<GroceryInventoryExtension, String> {

  Optional<GroceryInventoryExtension> findByShopIdAndInventoryId(String shopId, String inventoryId);

  List<GroceryInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
