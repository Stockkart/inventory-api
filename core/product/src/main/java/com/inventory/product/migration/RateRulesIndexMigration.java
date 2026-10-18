package com.inventory.product.migration;

import com.inventory.product.domain.model.ShopRateRulesDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Idempotent: makes {@code shopId} unique on {@code shop_rate_rules}. Automatic index creation is
 * off, so {@code @Indexed(unique = true)} on the document would do nothing.
 *
 * <p>Also gives documents saved before {@code ShopRateRulesDocument.version} existed a version of
 * 0. Spring Data treats a versioned document with a null version as new and inserts it, which
 * would fail on its existing id; only documents still without the field are touched.
 */
@Component
@Slf4j
public class RateRulesIndexMigration {

  static final String SHOP_UNIQUE_INDEX = "shop_rate_rules_shop_unique_idx";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(7)
  public void run() {
    try {
      mongoTemplate
          .indexOps(ShopRateRulesDocument.class)
          .ensureIndex(
              new Index().on("shopId", Sort.Direction.ASC).unique().named(SHOP_UNIQUE_INDEX));
    } catch (RuntimeException e) {
      log.error("[rate-rules-index] failed to ensure shopId index: {}", e.getMessage(), e);
    }
    try {
      Query unversioned = Query.query(Criteria.where("version").exists(false));
      long before = mongoTemplate.count(unversioned, ShopRateRulesDocument.class);
      if (before > 0) {
        long updated = mongoTemplate
            .updateMulti(unversioned, new Update().set("version", 0L), ShopRateRulesDocument.class)
            .getModifiedCount();
        log.info("[rate-rules-index] versioned {} of {} documents without a version", updated, before);
      }
    } catch (RuntimeException e) {
      log.error("[rate-rules-index] failed to version documents: {}", e.getMessage(), e);
    }
  }
}
