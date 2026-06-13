package com.inventory.plugins.medical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MedicalExtensionMongoRepository
    extends MongoRepository<MedicalInventoryExtension, String> {

  Optional<MedicalInventoryExtension> findByShopIdAndInventoryId(
      String shopId, String inventoryId);

  List<MedicalInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
