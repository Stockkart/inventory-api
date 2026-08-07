package com.inventory.plugins.supermarket.domain.repository;

import com.inventory.plugins.supermarket.domain.model.SupermarketInventoryExtension;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SupermarketExtensionMongoRepository
    extends MongoRepository<SupermarketInventoryExtension, String> {

  Optional<SupermarketInventoryExtension> findByShopIdAndInventoryId(String shopId, String inventoryId);

  List<SupermarketInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
