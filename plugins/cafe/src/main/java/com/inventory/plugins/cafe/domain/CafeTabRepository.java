package com.inventory.plugins.cafe.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeTabRepository extends MongoRepository<CafeTab, String> {

  List<CafeTab> findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
      String shopId, String userId, CafeTabStatus status);

  Optional<CafeTab> findByIdAndShopIdAndUserId(String id, String shopId, String userId);

  long countByShopIdAndUserIdAndStatus(String shopId, String userId, CafeTabStatus status);
}
