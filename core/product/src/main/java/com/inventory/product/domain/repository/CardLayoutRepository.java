package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.CardLayoutDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Per-shop, per-surface card layouts. */
@Repository
public interface CardLayoutRepository extends MongoRepository<CardLayoutDocument, String> {

  /** Every surface the shop has saved a layout for. */
  List<CardLayoutDocument> findByShopId(String shopId);

  Optional<CardLayoutDocument> findByShopIdAndSurfaceId(String shopId, String surfaceId);
}
