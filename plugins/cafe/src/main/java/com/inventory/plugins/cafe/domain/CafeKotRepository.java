package com.inventory.plugins.cafe.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeKotRepository extends MongoRepository<CafeKot, String> {

  Optional<CafeKot> findByIdAndShopId(String id, String shopId);

  /**
   * Every ticket this punch (or cancel) has already written. The source of truth for what a recovery must
   * <b>not</b> renumber: a {@code kotNo} on paper in a kitchen has to stay valid.
   */
  List<CafeKot> findByShopIdAndPunchId(String shopId, String punchId);

  /**
   * Every ticket a bill has issued, newest first -- what the counter reads to reprint a round the
   * kitchen never got. Shop is part of the query, not a filter applied afterwards.
   */
  List<CafeKot> findByShopIdAndPurchaseIdOrderByCreatedAtDesc(String shopId, String purchaseId);
}
