package com.inventory.plugins.cafe.repository;

import com.inventory.plugins.cafe.domain.CafeKot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeKotRepository extends MongoRepository<CafeKot, String> {

  Optional<CafeKot> findByIdAndShopId(String id, String shopId);

  List<CafeKot> findByShopIdAndOrderId(String shopId, String orderId);

  List<CafeKot> findByShopIdAndPunchId(String shopId, String punchId);

  /** Used when redriving a punch that was claimed but never finished. */
  void deleteByShopIdAndPunchId(String shopId, String punchId);
}
