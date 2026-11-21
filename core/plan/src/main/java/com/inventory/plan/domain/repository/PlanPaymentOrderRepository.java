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

  /**
   * First fulfilled order that bought a plan. {@code addOnOnly} is passed as true: "Not" matches
   * {@code $ne: true}, so orders saved before the field existed (no addOnOnly) still count.
   */
  Optional<PlanPaymentOrder> findFirstByShopIdAndStatusAndAddOnOnlyNotOrderByFulfilledAtAsc(String shopId,
      String status, boolean addOnOnly);

  List<PlanPaymentOrder> findByShopIdAndStatusIn(String shopId, Collection<String> statuses);

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
  /** The newest still-payable order for the same cart, for checkouts sent without Idempotency-Key. */
  Optional<PlanPaymentOrder> findFirstByShopIdAndRequestHashAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
      String shopId, String requestHash, java.util.Collection<String> statuses, java.time.Instant now);
}
