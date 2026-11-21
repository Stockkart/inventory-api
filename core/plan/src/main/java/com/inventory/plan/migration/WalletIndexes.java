package com.inventory.plan.migration;

import com.inventory.plan.domain.model.ShopCreditEntry;
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
 * Wallet ledger indexes. Auto index creation is off, so they are ensured on startup.
 *
 * <p>A reference id is unique per shop ({@code shopId_referenceId_unique}): the same admin
 * adjustment id on two shops is two changes. The earlier global {@code referenceId_unique} index is
 * dropped once the per-shop one exists; every pair it allowed is still unique per shop.
 */
@Component
@Slf4j
public class WalletIndexes {

  static final String SHOP_REFERENCE_INDEX = "shopId_referenceId_unique";
  static final String LEGACY_REFERENCE_INDEX = "referenceId_unique";

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(25)
  public void ensureIndexes() {
    var entries = mongoTemplate.indexOps(ShopCreditEntry.class);
    entries.ensureIndex(new Index().on("shopId", Sort.Direction.ASC).on("referenceId", Sort.Direction.ASC)
        .unique().named(SHOP_REFERENCE_INDEX));
    if (entries.getIndexInfo().stream().anyMatch(i -> LEGACY_REFERENCE_INDEX.equals(i.getName()))) {
      entries.dropIndex(LEGACY_REFERENCE_INDEX);
      log.info("Dropped wallet ledger index {} (replaced by {})", LEGACY_REFERENCE_INDEX, SHOP_REFERENCE_INDEX);
    }
    entries.ensureIndex(new Index().on("shopId", Sort.Direction.ASC).on("sourceId", Sort.Direction.ASC)
        .on("source", Sort.Direction.ASC).named("shopId_sourceId_source"));
    entries.ensureIndex(new Index().on("shopId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
        .named("shopId_createdAt"));
  }
}
