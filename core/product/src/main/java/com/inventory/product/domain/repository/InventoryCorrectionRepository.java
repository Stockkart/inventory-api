package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.InventoryCorrection;
import com.inventory.product.domain.model.enums.InventoryCorrectionStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InventoryCorrectionRepository extends MongoRepository<InventoryCorrection, String> {
  Page<InventoryCorrection> findByShopId(String shopId, Pageable pageable);

  Page<InventoryCorrection> findByShopIdAndStatus(
      String shopId, InventoryCorrectionStatus status, Pageable pageable);

  List<InventoryCorrection> findByShopIdAndStatusOrderByCreatedAtDesc(
      String shopId, InventoryCorrectionStatus status);
}

