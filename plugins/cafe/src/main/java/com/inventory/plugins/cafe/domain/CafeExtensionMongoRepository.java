package com.inventory.plugins.cafe.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeExtensionMongoRepository
    extends MongoRepository<CafeInventoryExtension, String> {

  Optional<CafeInventoryExtension> findByShopIdAndInventoryId(String shopId, String inventoryId);

  List<CafeInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
