package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.StockEntryEstimate;
import com.inventory.product.domain.model.enums.StockEntryEstimateState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockEntryEstimateRepository extends MongoRepository<StockEntryEstimate, String> {

  Optional<StockEntryEstimate> findByIdAndShopId(String id, String shopId);

  Page<StockEntryEstimate> findByShopIdAndState(
      String shopId, StockEntryEstimateState state, Pageable pageable);

  Page<StockEntryEstimate> findByShopIdAndStateNot(
      String shopId, StockEntryEstimateState state, Pageable pageable);

  List<StockEntryEstimate> findByShopIdAndStateOrderByUpdatedAtDesc(
      String shopId, StockEntryEstimateState state);

  long countByShopIdAndState(String shopId, StockEntryEstimateState state);
}
