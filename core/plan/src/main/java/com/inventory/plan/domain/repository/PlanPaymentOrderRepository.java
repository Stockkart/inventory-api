package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlanPaymentOrderRepository extends MongoRepository<PlanPaymentOrder, String> {

  Optional<PlanPaymentOrder> findByIdAndShopId(String id, String shopId);

  Optional<PlanPaymentOrder> findByProviderAndProviderOrderId(String provider, String providerOrderId);
}
