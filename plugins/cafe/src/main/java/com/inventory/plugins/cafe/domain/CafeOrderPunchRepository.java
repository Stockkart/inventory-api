package com.inventory.plugins.cafe.domain;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeOrderPunchRepository extends MongoRepository<CafeOrderPunch, String> {

  Optional<CafeOrderPunch> findByShopIdAndIdempotencyKey(String shopId, String idempotencyKey);

  long countByShopIdAndOrderId(String shopId, String orderId);
}
