package com.inventory.plugins.cafe.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeKotRepository extends MongoRepository<CafeKot, String> {

  Optional<CafeKot> findByIdAndShopId(String id, String shopId);

  /**
   * Every ticket this flush has already written. The source of truth for what a recovery must
   * <b>not</b> renumber: a {@code kotNo} on paper in a kitchen has to stay valid.
   */
  List<CafeKot> findByShopIdAndFlushId(String shopId, String flushId);
}
