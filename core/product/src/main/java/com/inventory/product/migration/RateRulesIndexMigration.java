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
import org.springframework.stereotype.Component;

/**
 * Idempotent: makes {@code shopId} unique on {@code shop_rate_rules}. Automatic index creation is
 * off, so {@code @Indexed(unique = true)} on the document would do nothing.
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
  }
}
