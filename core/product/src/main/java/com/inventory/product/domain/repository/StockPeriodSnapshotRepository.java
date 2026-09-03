package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.StockPeriodSnapshot;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockPeriodSnapshotRepository extends MongoRepository<StockPeriodSnapshot, String> {

  Optional<StockPeriodSnapshot> findByShopIdAndPeriodEnd(String shopId, LocalDate periodEnd);
}
