package com.inventory.plan.migration;

import com.inventory.plan.domain.model.ShopCreditEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Wallet ledger indexes. Auto index creation is off, so they are ensured on startup. */
@Component
public class WalletIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(25)
  public void ensureIndexes() {
    var entries = mongoTemplate.indexOps(ShopCreditEntry.class);
    entries.ensureIndex(new Index().on("referenceId", Sort.Direction.ASC).unique().named("referenceId_unique"));
    entries.ensureIndex(new Index().on("shopId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
        .named("shopId_createdAt"));
  }
}
