package com.inventory.plugins.fmcg.domain.repository;

import com.inventory.plugins.fmcg.domain.model.FmcgInventoryExtension;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FmcgExtensionMongoRepository
    extends MongoRepository<FmcgInventoryExtension, String> {

  Optional<FmcgInventoryExtension> findByShopIdAndInventoryId(String shopId, String inventoryId);

  List<FmcgInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
