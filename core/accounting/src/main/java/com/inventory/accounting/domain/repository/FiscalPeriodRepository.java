package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.FiscalPeriod;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FiscalPeriodRepository extends MongoRepository<FiscalPeriod, String> {

  Optional<FiscalPeriod> findByShopIdAndYearAndMonth(String shopId, int year, int month);

  List<FiscalPeriod> findByShopIdOrderByYearDescMonthDesc(String shopId);
}
