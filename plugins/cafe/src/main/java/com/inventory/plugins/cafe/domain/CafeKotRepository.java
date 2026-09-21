package com.inventory.plugins.cafe.domain;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeKotRepository extends MongoRepository<CafeKot, String> {

  Optional<CafeKot> findByIdAndShopId(String id, String shopId);
}
