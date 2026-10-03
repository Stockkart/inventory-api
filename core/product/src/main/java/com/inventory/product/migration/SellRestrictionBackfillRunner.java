package com.inventory.product.migration;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.InventorySellRestriction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Sets {@code sellRestriction=ESTIMATE_ONLY} on legacy BASIC inventory lots that have no
 * restriction yet. Idempotent; safe to re-run.
 */
@Component
@Slf4j
public class SellRestrictionBackfillRunner {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Value("${stockkart.sell-restriction-backfill.enabled:true}")
  private boolean enabled;

  @Value("${stockkart.sell-restriction-backfill.dry-run:false}")
  private boolean dryRun;

  @Order(40)
  @EventListener(ApplicationReadyEvent.class)
  public void run() {
    if (!enabled) {
      return;
    }
    Query query =
        new Query(
            Criteria.where("billingMode")
                .is(BillingMode.BASIC.name())
                .and("sellRestriction")
                .exists(false));
    long count = mongoTemplate.count(query, "inventory");
    log.info(
        "SellRestriction backfill: {} BASIC inventory rows missing sellRestriction (dryRun={})",
        count,
        dryRun);
    if (dryRun || count == 0) {
      return;
    }
    Update update =
        new Update().set("sellRestriction", InventorySellRestriction.ESTIMATE_ONLY.name());
    var result = mongoTemplate.updateMulti(query, update, "inventory");
    log.info(
        "SellRestriction backfill: updated {} inventory rows to ESTIMATE_ONLY",
        result.getModifiedCount());
  }
}
