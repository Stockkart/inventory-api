package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlanPaymentOrderRepository extends MongoRepository<PlanPaymentOrder, String> {

  Optional<PlanPaymentOrder> findByIdAndShopId(String id, String shopId);

  Optional<PlanPaymentOrder> findByProviderAndProviderOrderId(String provider, String providerOrderId);

  Optional<PlanPaymentOrder> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);

  Optional<PlanPaymentOrder> findFirstByShopIdAndStatusOrderByFulfilledAtAsc(String shopId, String status);

  Optional<PlanPaymentOrder> findByShopIdAndIdempotencyKey(String shopId, String idempotencyKey);

  @Query("{ 'status': { $in: ?0 }, 'paidAt': { $gte: ?1, $lt: ?2 } }")
  List<PlanPaymentOrder> findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThan(
      Collection<String> statuses, Instant from, Instant to);

  @Query("{ 'refundedAt': { $gte: ?0, $lt: ?1 } }")
  List<PlanPaymentOrder> findByRefundedAtGreaterThanEqualAndRefundedAtLessThan(Instant from, Instant to);

  /** Paid orders of these shops from before {@code before}; orders older than paidAt count by createdAt. */
  @Query(value = "{ 'shopId': { $in: ?0 }, 'status': { $in: ?1 }, $or: [ { 'paidAt': { $lt: ?2 } },"
      + " { 'paidAt': null, 'createdAt': { $lt: ?2 } } ] }", fields = "{ 'shopId': 1 }")
  List<PlanPaymentOrder> findPaidBefore(Collection<String> shopIds, Collection<String> statuses, Instant before);
}
