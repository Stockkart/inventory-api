package com.inventory.user.migration;

import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.model.ShopVendor;
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
 * Idempotent: ensures {@code {shopId, customerId}} on {@code shop_customers} and {@code {shopId,
 * vendorId}} on {@code shop_vendors}, skipping a collection that already has an index on those
 * keys (production's {@code shop_customers} has a unique one). Customer and vendor search read a shop's ids from
 * these link collections ({@code ShopPartySearch}); with the index that read is index-only, without
 * it ({@code shop_vendors} had none) it scans every shop's links. Automatic index creation is off.
 */
@Component
@Slf4j
public class ShopPartyIndexMigration {

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(11)
  public void run() {
    ensure(ShopCustomer.class, "customerId");
    ensure(ShopVendor.class, "vendorId");
  }

  private void ensure(Class<?> link, String idField) {
    String collection = mongoTemplate.getCollectionName(link);
    try {
      var ops = mongoTemplate.indexOps(link);
      // shop_customers already has it as a unique index; asking again without `unique` would fail
      for (var info : ops.getIndexInfo()) {
        var fields = info.getIndexFields();
        if (fields.size() == 2
            && "shopId".equals(fields.get(0).getKey())
            && idField.equals(fields.get(1).getKey())) {
          log.info("[shop-party-index] {} already on {}", info.getName(), collection);
          return;
        }
      }
      String name = ops.ensureIndex(new Index().on("shopId", Sort.Direction.ASC).on(idField, Sort.Direction.ASC));
      log.info("[shop-party-index] ensured {} on {}", name, collection);
    } catch (RuntimeException e) {
      log.error("[shop-party-index] failed on {}: {}", collection, e.getMessage(), e);
    }
  }
}
