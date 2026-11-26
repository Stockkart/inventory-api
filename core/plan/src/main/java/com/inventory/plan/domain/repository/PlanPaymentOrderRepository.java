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

  /** Only the fields the plan MIS reads (not idempotency, gateway or fulfilment fields). */
  String MIS_FIELDS = "{ 'shopId': 1, 'status': 1, 'amount': 1, 'discountTotal': 1, 'walletCredit': 1,"
      + " 'taxableAmount': 1, 'gstAmount': 1, 'gstRatePercent': 1, 'refundedAmount': 1, 'refundSource': 1,"
      + " 'items': 1, 'planCode': 1, 'planName': 1, 'paidAt': 1, 'refundedAt': 1 }";

  @Query(value = "{ 'status': { $in: ?0 }, 'paidAt': { $gte: ?1, $lt: ?2 } }", fields = MIS_FIELDS)
  List<PlanPaymentOrder> findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThan(
      Collection<String> statuses, Instant from, Instant to);

  @Query(value = "{ 'refundedAt': { $gte: ?0, $lt: ?1 } }", fields = MIS_FIELDS)
  List<PlanPaymentOrder> findByRefundedAtGreaterThanEqualAndRefundedAtLessThan(Instant from, Instant to);

  /**
   * Paid orders of these shops from before {@code before}; orders older than paidAt count by createdAt.
   * Each {@code $or} branch starts with shopId and status, so both are served by index
   * {@code shop_status_paidAt} (the legacy branch as {@code paidAt: null}, then createdAt on the few
   * legacy rows it finds).
   */
  @Query(value = "{ $or: [ { 'shopId': { $in: ?0 }, 'status': { $in: ?1 }, 'paidAt': { $lt: ?2 } },"
      + " { 'shopId': { $in: ?0 }, 'status': { $in: ?1 }, 'paidAt': null, 'createdAt': { $lt: ?2 } } ] }",
      fields = "{ 'shopId': 1 }")
  List<PlanPaymentOrder> findPaidBefore(Collection<String> shopIds, Collection<String> statuses, Instant before);
  /** The newest still-payable order for the same cart, for checkouts sent without Idempotency-Key. */
  Optional<PlanPaymentOrder> findFirstByShopIdAndRequestHashAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
      String shopId, String requestHash, java.util.Collection<String> statuses, java.time.Instant now);
}
