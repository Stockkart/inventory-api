package com.inventory.plugins.cafe.repository;

import com.inventory.plugins.cafe.domain.CafeOrder;
import com.inventory.plugins.cafe.domain.CafeOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CafeOrderRepository extends MongoRepository<CafeOrder, String> {

  /** Every lookup is shop-scoped: ids travel through REST, so isolation is structural. */
  Optional<CafeOrder> findByIdAndShopId(String id, String shopId);

  List<CafeOrder> findByShopIdAndStatusOrderByOrderNoDesc(String shopId, CafeOrderStatus status);
}
