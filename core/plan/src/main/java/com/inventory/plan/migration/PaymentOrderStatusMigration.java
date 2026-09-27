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
import org.springframework.data.mongodb.core.index.Index;
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
    orders.ensureIndex(new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("idempotencyKey", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("idempotencyKey").exists(true)))
        .named("shop_idempotency_key_unique"));
    orders.ensureIndex(new Index()
        .on("status", Sort.Direction.ASC)
        .on("expiresAt", Sort.Direction.ASC)
        .named("status_expiresAt"));
    orders.ensureIndex(new Index()
        .on("status", Sort.Direction.ASC)
        .on("claimedAt", Sort.Direction.ASC)
        .named("status_claimedAt"));
    mongoTemplate.indexOps(PlanTransaction.class).ensureIndex(new Index()
        .on("paymentOrderId", Sort.Direction.ASC)
        .named("paymentOrderId"));
  }
}
