package com.inventory.analytics.utils;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.catalog.InventoryProductReadHandler;
import com.inventory.product.domain.model.pricing.InventoryPricingReadHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * A shop's lots, handed over a batch at a time, for analytics that look at every lot.
 *
 * <p>The Inventory and Vendors tabs used to load every lot of the shop at once, each with its
 * product and pricing record, then compute. Here one cursor reads the lots (only the stored fields
 * those tabs use), and each batch gets its product and pricing filled in exactly as a repository
 * {@code find*} does (same handlers as the read aspects), is handed to the caller, then dropped.
 * Memory holds one batch, not the shop. Lots come in the same order as {@code findByShopId}
 * (same filter, no sort), so anything that keeps input order keeps it.
 */
@Component
public class ShopLotBatches {

  /** Lots per batch. */
  public static final int BATCH = 2000;

  /**
   * Stored fields the two tabs read, plus {@code productId} / {@code pricingId} / {@code
   * billingMode}, which the product and pricing hydration read.
   */
  static final String[] FIELDS = {
    "lotId", "productId", "pricingId", "billingMode", "shopId", "vendorId", "location",
    "receivedCount", "soldCount", "currentCount", "receivedBaseCount", "soldBaseCount",
    "currentBaseCount", "receivedDate"
  };

  private final MongoTemplate mongoTemplate;
  private final InventoryProductReadHandler productHydration;
  private final InventoryPricingReadHandler pricingHydration;

  public ShopLotBatches(
      MongoTemplate mongoTemplate,
      InventoryProductReadHandler productHydration,
      InventoryPricingReadHandler pricingHydration) {
    this.mongoTemplate = mongoTemplate;
    this.productHydration = productHydration;
    this.pricingHydration = pricingHydration;
  }

  /** Calls {@code each} with every batch of the shop's lots, in order. */
  public void forEach(String shopId, Consumer<List<Inventory>> each) {
    Query query = new Query(Criteria.where("shopId").is(shopId)).cursorBatchSize(BATCH);
    query.fields().include(FIELDS);
    try (Stream<Inventory> lots = mongoTemplate.stream(query, Inventory.class)) {
      Iterator<Inventory> it = lots.iterator();
      List<Inventory> batch = new ArrayList<>(BATCH);
      while (it.hasNext()) {
        batch.add(it.next());
        if (batch.size() == BATCH) {
          hand(batch, each);
          batch = new ArrayList<>(BATCH);
        }
      }
      if (!batch.isEmpty()) {
        hand(batch, each);
      }
    }
  }

  private void hand(List<Inventory> batch, Consumer<List<Inventory>> each) {
    productHydration.enrich(batch);
    pricingHydration.enrich(batch);
    each.accept(batch);
  }
}
