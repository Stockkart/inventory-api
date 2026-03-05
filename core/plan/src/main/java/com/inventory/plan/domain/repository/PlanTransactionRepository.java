package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.PlanTransaction;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlanTransactionRepository extends MongoRepository<PlanTransaction, String> {

  List<PlanTransaction> findByShopId(String shopId, Sort sort);
}
