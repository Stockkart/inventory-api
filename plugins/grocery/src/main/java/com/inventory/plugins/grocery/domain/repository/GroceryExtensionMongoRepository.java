package com.inventory.plugins.grocery.domain.repository;

import com.inventory.plugins.grocery.domain.model.GroceryInventoryExtension;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GroceryExtensionMongoRepository
    extends MongoRepository<GroceryInventoryExtension, String> {

  Optional<GroceryInventoryExtension> findByShopIdAndInventoryId(String shopId, String inventoryId);

  /** One reply for up to 5,000 lots (the driver's default first batch is 101; the rest cost a getMore). */
  @Meta(cursorBatchSize = 5000)
  List<GroceryInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
