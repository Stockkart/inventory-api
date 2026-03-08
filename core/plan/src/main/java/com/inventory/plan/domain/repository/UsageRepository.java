package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.Usage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface UsageRepository extends MongoRepository<Usage, String> {

  Optional<Usage> findByShopIdAndMonth(String shopId, String month);

  List<Usage> findByShopIdOrderByMonthDesc(String shopId, Pageable pageable);
}
