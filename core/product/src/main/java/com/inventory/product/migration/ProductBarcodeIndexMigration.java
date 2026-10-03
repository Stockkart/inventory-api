package com.inventory.product.migration;

import com.inventory.product.domain.model.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Idempotent: replaces the old unique {@code (shopId, barcode)} index with a non-unique one so
 * several products can share a barcode. Automatic index creation is off, so the unique index
 * applied earlier has to be dropped explicitly.
 */
@Component
@Slf4j
public class ProductBarcodeIndexMigration {

  static final String LEGACY_UNIQUE_INDEX = "shop_barcode_unique_idx";
  static final String BARCODE_INDEX = "shop_barcode_idx";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(5)
  public void run() {
    try {
      IndexOperations ops = mongoTemplate.indexOps(Product.class);
      boolean hasLegacy =
          ops.getIndexInfo().stream().anyMatch(i -> LEGACY_UNIQUE_INDEX.equals(i.getName()));
      if (hasLegacy) {
        ops.dropIndex(LEGACY_UNIQUE_INDEX);
        log.info("[product-barcode-index] dropped unique index {}", LEGACY_UNIQUE_INDEX);
      }
      ops.ensureIndex(
          new Index()
              .on("shopId", Sort.Direction.ASC)
              .on("barcode", Sort.Direction.ASC)
              .sparse()
              .named(BARCODE_INDEX));
    } catch (RuntimeException e) {
      log.error("[product-barcode-index] failed to migrate barcode index: {}", e.getMessage(), e);
    }
  }
}
