package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.ShopRateRulesDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ShopRateRulesRepository extends MongoRepository<ShopRateRulesDocument, String> {

  Optional<ShopRateRulesDocument> findByShopId(String shopId);
}
