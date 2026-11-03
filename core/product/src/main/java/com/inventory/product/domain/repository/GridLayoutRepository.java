package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.GridLayoutDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Per-shop, per-grid column layouts. */
@Repository
public interface GridLayoutRepository extends MongoRepository<GridLayoutDocument, String> {

  Optional<GridLayoutDocument> findByShopIdAndGridId(String shopId, String gridId);

  void deleteByShopIdAndGridId(String shopId, String gridId);
}
