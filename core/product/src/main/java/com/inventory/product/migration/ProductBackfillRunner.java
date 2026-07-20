package com.inventory.product.migration;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-off, idempotent backfill that gives every legacy {@link Inventory} row a {@code productId} by
 * grouping rows into shop-scoped catalog {@link com.inventory.product.domain.model.Product}s.
 *
 * <p>Runs on startup only when {@code stockkart.product-backfill.enabled=true}. Defaults to
 * {@code dry-run=true} which logs what would happen without writing. Safe to re-run: rows already
 * linked are skipped and product resolution reuses existing catalog products, so a crash mid-run
 * simply resumes on the next run.
 */
@Component
@Slf4j
public class ProductBackfillRunner {

  @Autowired private InventoryRepository inventoryRepository;
  @Autowired private ProductService productService;
  @Autowired private MongoTemplate mongoTemplate;

  @Value("${stockkart.product-backfill.enabled:false}")
  private boolean enabled;

  @Value("${stockkart.product-backfill.dry-run:true}")
  private boolean dryRun;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void run() {
    if (!enabled) {
      return;
    }
    List<String> shopIds = mongoTemplate.findDistinct(
        Query.query(Criteria.where("productId").is(null)),
        "shopId",
        Inventory.class,
        String.class);

    log.info("[product-backfill] starting (dryRun={}) across {} shop(s)", dryRun, shopIds.size());
    long totalLinked = 0;
    long totalProducts = 0;
    for (String shopId : shopIds) {
      if (!StringUtils.hasText(shopId)) {
        continue;
      }
      long[] shopResult = processShop(shopId);
      totalLinked += shopResult[0];
      totalProducts += shopResult[1];
    }
    log.info("[product-backfill] done (dryRun={}): linked {} inventory row(s) to {} product(s)",
        dryRun, totalLinked, totalProducts);
  }

  /** @return [linkedRowCount, distinctProductCount] for the shop. */
  private long[] processShop(String shopId) {
    List<Inventory> rows = inventoryRepository.findByShopIdAndProductIdIsNull(shopId);
    if (rows.isEmpty()) {
      return new long[] {0, 0};
    }

    if (dryRun) {
      Set<String> keys = new HashSet<>();
      for (Inventory inv : rows) {
        keys.add(productService.identityKey(inv, shopId));
      }
      log.info("[product-backfill] shop {}: {} row(s) -> {} product(s) (dry-run)",
          shopId, rows.size(), keys.size());
      return new long[] {rows.size(), keys.size()};
    }

    Set<String> productIds = new HashSet<>();
    long linked = 0;
    for (Inventory inv : rows) {
      // Identity is @Transient now; a row here without a mapped name means its identity was never
      // migrated (this backfill must run BEFORE identity is made transient). Skip to avoid creating
      // a product with null identity.
      if (!StringUtils.hasText(inv.getName())) {
        log.warn("[product-backfill] shop {}: skipping inventory {} — no readable identity "
            + "(run backfill before marking identity @Transient)", shopId, inv.getId());
        continue;
      }
      // Reuse the live resolution logic: matches an existing product for this identity or creates
      // one. Because created products persist within the loop, later rows dedupe against them.
      String productId = productService.resolveForRegistration(null, inv, shopId);
      inv.setProductId(productId);
      inventoryRepository.save(inv);
      productIds.add(productId);
      linked++;
    }
    log.info("[product-backfill] shop {}: linked {} row(s) to {} product(s)",
        shopId, linked, productIds.size());
    return new long[] {linked, productIds.size()};
  }
}
