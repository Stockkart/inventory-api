package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.ShopMenuDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ShopMenuRepository extends MongoRepository<ShopMenuDocument, String> {

  Optional<ShopMenuDocument> findByShopIdAndVerticalId(String shopId, String verticalId);
}
