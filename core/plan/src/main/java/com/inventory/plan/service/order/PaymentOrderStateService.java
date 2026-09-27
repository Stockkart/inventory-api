package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Every order status change, each a single conditional update on the current status. A caller that
 * loses a race gets empty/false and re-reads, never overwrites.
 */
@Service
public class PaymentOrderStateService {

  @Autowired
  private MongoTemplate mongoTemplate;

  /** CREATED → PAYMENT_PENDING with the provider order. */
  public Optional<PlanPaymentOrder> attachProviderOrder(String id, String providerOrderId, Instant now) {
    Query query = byIdAndStatus(id, List.of(PlanPaymentConstants.STATUS_CREATED));
    Update update = new Update()
        .set("providerOrderId", providerOrderId)
        .set("status", PlanPaymentConstants.STATUS_PAYMENT_PENDING)
        .set("updatedAt", now);
    return Optional.ofNullable(mongoTemplate.findAndModify(query, update, returnNew(), PlanPaymentOrder.class));
  }

  /**
   * A confirmed payment → PAID. Accepted from open states, and from ended-unpaid states as a late
   * payment (flagged in the same update). Returns the paid order, or empty when it was already paid.
   */
  public Optional<PlanPaymentOrder> markPaid(String id, String providerPaymentId, String paymentMethod, Instant now) {
    Optional<PlanPaymentOrder> onTime = transitionToPaid(id, PlanPaymentConstants.OPEN_STATUSES,
        false, providerPaymentId, paymentMethod, now);
    if (onTime.isPresent()) {
      return onTime;
    }
    return transitionToPaid(id, PlanPaymentConstants.UNPAID_ENDED_STATUSES, true,
        providerPaymentId, paymentMethod, now);
  }

  private Optional<PlanPaymentOrder> transitionToPaid(String id, List<String> from, boolean late,
      String providerPaymentId, String paymentMethod, Instant now) {
    Update update = new Update()
        .set("status", PlanPaymentConstants.STATUS_PAID)
        .set("latePayment", late)
        .set("providerPaymentId", providerPaymentId)
        .set("paymentMethod", paymentMethod)
        .set("paidAt", now)
        .set("updatedAt", now);
    return Optional.ofNullable(mongoTemplate.findAndModify(byIdAndStatus(id, from), update, returnNew(),
        PlanPaymentOrder.class));
  }

  /**
   * PAID, or FULFILLING with a claim older than {@code lease}, → FULFILLING claimed now. The lease
   * lets a retry take over from a crashed attempt.
   */
  public Optional<PlanPaymentOrder> claim(String id, Instant now, Duration lease) {
    Query query = new Query(Criteria.where("_id").is(id).orOperator(
        Criteria.where("status").is(PlanPaymentConstants.STATUS_PAID),
        Criteria.where("status").is(PlanPaymentConstants.STATUS_FULFILLING)
            .and("claimedAt").lt(now.minus(lease))));
    Update update = new Update()
        .set("status", PlanPaymentConstants.STATUS_FULFILLING)
        .set("claimedAt", now)
        .inc("fulfilmentAttempts", 1)
        .set("updatedAt", now);
    return Optional.ofNullable(mongoTemplate.findAndModify(query, update, returnNew(), PlanPaymentOrder.class));
  }

  /** FULFILLING under this claim → FULFILLED. */
  public boolean complete(String id, Instant claimedAt, Instant now) {
    Update update = new Update()
        .set("status", PlanPaymentConstants.STATUS_FULFILLED)
        .set("fulfilledAt", now)
        .set("updatedAt", now)
        .unset("failureReason");
    return mongoTemplate.updateFirst(byClaim(id, claimedAt), update, PlanPaymentOrder.class)
        .getModifiedCount() > 0;
  }

  /** FULFILLING under this claim → FULFILMENT_FAILED, for an operator. */
  public boolean failFulfilment(String id, Instant claimedAt, String reason, Instant now) {
    Update update = new Update()
        .set("status", PlanPaymentConstants.STATUS_FULFILMENT_FAILED)
        .set("failureReason", reason)
        .set("updatedAt", now);
    return mongoTemplate.updateFirst(byClaim(id, claimedAt), update, PlanPaymentOrder.class)
        .getModifiedCount() > 0;
  }

  /** Keeps the claim for the lease-based retry, recording why this attempt failed. */
  public void recordAttemptFailure(String id, Instant claimedAt, String reason, Instant now) {
    mongoTemplate.updateFirst(byClaim(id, claimedAt),
        new Update().set("failureReason", reason).set("updatedAt", now), PlanPaymentOrder.class);
  }

  /** An open order → {@code status} (PAYMENT_FAILED, EXPIRED or CANCELLED). */
  public boolean endUnpaid(String id, String status, String reason, Instant now) {
    Update update = new Update().set("status", status).set("updatedAt", now);
    if (reason != null) {
      update.set("failureReason", reason);
    }
    return mongoTemplate.updateFirst(byIdAndStatus(id, PlanPaymentConstants.OPEN_STATUSES), update,
        PlanPaymentOrder.class).getModifiedCount() > 0;
  }

  /** Open orders past their expiry. Orders from before expiresAt existed use createdAt + ttl. */
  public List<PlanPaymentOrder> findExpirable(Instant now, Duration ttl, int limit) {
    Query query = new Query(Criteria.where("status").in(PlanPaymentConstants.OPEN_STATUSES).orOperator(
        Criteria.where("expiresAt").lt(now),
        Criteria.where("expiresAt").is(null).and("createdAt").lt(now.minus(ttl))))
        .with(Sort.by("createdAt"))
        .limit(limit);
    return mongoTemplate.find(query, PlanPaymentOrder.class);
  }

  /** Paid but never claimed, or claimed by an attempt that stopped renewing. */
  public List<PlanPaymentOrder> findStuck(Instant now, Duration paidGrace, Duration lease, int limit) {
    Query query = new Query(new Criteria().orOperator(
        Criteria.where("status").is(PlanPaymentConstants.STATUS_PAID).and("paidAt").lt(now.minus(paidGrace)),
        Criteria.where("status").is(PlanPaymentConstants.STATUS_FULFILLING)
            .and("claimedAt").lt(now.minus(lease))))
        .with(Sort.by("paidAt"))
        .limit(limit);
    return mongoTemplate.find(query, PlanPaymentOrder.class);
  }

  private static Query byIdAndStatus(String id, List<String> statuses) {
    return new Query(Criteria.where("_id").is(id).and("status").in(statuses));
  }

  private static Query byClaim(String id, Instant claimedAt) {
    return new Query(Criteria.where("_id").is(id)
        .and("status").is(PlanPaymentConstants.STATUS_FULFILLING)
        .and("claimedAt").is(claimedAt));
  }

  private static FindAndModifyOptions returnNew() {
    return FindAndModifyOptions.options().returnNew(true);
  }
}
