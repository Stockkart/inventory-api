package com.inventory.product.migration;

import com.inventory.product.domain.model.GridLayoutDocument;
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
 * Idempotent: ensures the unique {@code (shopId, gridId)} index on {@code shop_grid_layouts}.
 * Automatic index creation is off, so the {@code @CompoundIndex} on {@link GridLayoutDocument} is
 * not applied by itself; without this index two first saves for a shop could both insert.
 */
@Component
@Slf4j
public class GridLayoutIndexMigration {

  static final String SHOP_GRID_INDEX = "shop_grid_idx";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(5)
  public void run() {
    try {
      mongoTemplate
          .indexOps(GridLayoutDocument.class)
          .ensureIndex(
              new Index()
                  .on("shopId", Sort.Direction.ASC)
                  .on("gridId", Sort.Direction.ASC)
                  .unique()
                  .named(SHOP_GRID_INDEX));
    } catch (RuntimeException e) {
      log.error("[grid-layout-index] failed to ensure {}: {}", SHOP_GRID_INDEX, e.getMessage(), e);
    }
  }
}
