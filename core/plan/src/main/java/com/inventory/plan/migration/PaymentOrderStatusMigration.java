package com.inventory.plan.migration;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.util.List;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Moves plan orders onto the state machine's status names (FAILED → PAYMENT_FAILED) and creates the
 * indexes it relies on. Idempotent; runs on every startup.
 */
@Component
@Slf4j
public class PaymentOrderStatusMigration {

  static final String PAYMENT_ORDER_UNIQUE = "paymentOrderId_unique";

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void migrate() {
    ensureIndexes();
    long renamed = mongoTemplate.updateMulti(
        new Query(Criteria.where("status").is(PlanPaymentConstants.LEGACY_STATUS_FAILED)),
        new Update().set("status", PlanPaymentConstants.STATUS_PAYMENT_FAILED),
        PlanPaymentOrder.class).getModifiedCount();
    if (renamed > 0) {
      log.info("Renamed {} plan order(s) from FAILED to PAYMENT_FAILED", renamed);
    }
  }

  void ensureIndexes() {
    var orders = mongoTemplate.indexOps(PlanPaymentOrder.class);
    ensure(orders, new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("idempotencyKey", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("idempotencyKey").exists(true)))
        .named("shop_idempotency_key_unique"));
    ensure(orders, new Index()
        .on("status", Sort.Direction.ASC)
        .on("expiresAt", Sort.Direction.ASC)
        .named("status_expiresAt"));
    ensure(orders, new Index()
        .on("status", Sort.Direction.ASC)
        .on("claimedAt", Sort.Direction.ASC)
        .named("status_claimedAt"));
    // refund webhooks find the order by the gateway's payment id
    ensure(orders, new Index()
        .on("provider", Sort.Direction.ASC)
        .on("providerPaymentId", Sort.Direction.ASC)
        .partial(PartialIndexFilter.of(Criteria.where("providerPaymentId").exists(true)))
        .named("provider_paymentId"));
    // webhook and refund look orders up by the gateway's order id; one order per gateway order
    ensure(orders, new Index()
        .on("provider", Sort.Direction.ASC)
        .on("providerOrderId", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("providerOrderId").type(2)))
        .named("provider_providerOrderId_unique"));
    // a shop's paid / fulfilled orders by date (purchase history, reports)
    ensure(orders, new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .on("paidAt", Sort.Direction.ASC)
        .named("shop_status_paidAt"));
    ensure(orders, new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .on("fulfilledAt", Sort.Direction.ASC)
        .named("shop_status_fulfilledAt"));
    // checkout without Idempotency-Key reuses the shop's open order for the same cart
    ensure(orders, new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("requestHash", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .named("shop_requestHash_status"));

    var transactions = mongoTemplate.indexOps(PlanTransaction.class);
    ensure(transactions, new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC)
        .named("shop_createdAt"));
    ensureUniquePaymentOrderId(transactions);
  }

  /**
   * One plan transaction per paid order. Rows written before this index (when the grant was a
   * check-then-insert) may already hold duplicates; then the unique index is not created, the old
   * non-unique one stays, and the duplicates are logged for an operator to merge.
   */
  void ensureUniquePaymentOrderId(IndexOperations transactions) {
    List<String> duplicates = mongoTemplate.aggregate(Aggregation.newAggregation(
            Aggregation.match(Criteria.where("paymentOrderId").type(2)),
            Aggregation.group("paymentOrderId").count().as("count"),
            Aggregation.match(Criteria.where("count").gt(1)),
            Aggregation.limit(20)),
        PlanTransaction.class, org.bson.Document.class)
        .getMappedResults().stream()
        .map(d -> String.valueOf(d.get("_id")))
        .toList();
    if (!duplicates.isEmpty()) {
      log.error("plan_transactions has more than one row for payment order(s) {}; index {} not created "
          + "until they are merged", duplicates, PAYMENT_ORDER_UNIQUE);
      ensure(transactions, new Index().on("paymentOrderId", Sort.Direction.ASC).named("paymentOrderId"));
      return;
    }
    // same key as the old non-unique index, which must go first (MongoDB refuses two indexes that
    // differ only in uniqueness); if the unique one then fails, the old one is put back
    boolean hasOld = transactions.getIndexInfo().stream().anyMatch(i -> "paymentOrderId".equals(i.getName()));
    if (hasOld) {
      transactions.dropIndex("paymentOrderId");
    }
    boolean unique = ensure(transactions, new Index()
        .on("paymentOrderId", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("paymentOrderId").type(2)))
        .named(PAYMENT_ORDER_UNIQUE));
    if (!unique) {
      ensure(transactions, new Index().on("paymentOrderId", Sort.Direction.ASC).named("paymentOrderId"));
    } else if (hasOld) {
      log.info("Replaced index paymentOrderId on plan_transactions with {}", PAYMENT_ORDER_UNIQUE);
    }
  }

  /** Creates one index; a failure (e.g. duplicates under a unique index) is logged, not fatal. */
  private boolean ensure(IndexOperations ops, Index index) {
    try {
      ops.ensureIndex(index);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not ensure index {}: {}", index.getIndexOptions().get("name"), e.getMessage());
      return false;
    }
  }
}
