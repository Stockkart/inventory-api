package com.inventory.plugins.sports.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SportsExtensionMongoRepository
    extends MongoRepository<SportsInventoryExtension, String> {

  Optional<SportsInventoryExtension> findByShopIdAndInventoryId(String shopId, String inventoryId);

  List<SportsInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
