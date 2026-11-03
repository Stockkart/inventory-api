package com.inventory.plugins.medical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MedicalExtensionMongoRepository
    extends MongoRepository<MedicalInventoryExtension, String> {

  Optional<MedicalInventoryExtension> findByShopIdAndInventoryId(
      String shopId, String inventoryId);

  /** One reply for up to 5,000 lots (the driver's default first batch is 101; the rest cost a getMore). */
  @Meta(cursorBatchSize = 5000)
  List<MedicalInventoryExtension> findByShopIdAndInventoryIdIn(
      String shopId, List<String> inventoryIds);
}
