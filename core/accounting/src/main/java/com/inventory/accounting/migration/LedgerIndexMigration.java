package com.inventory.accounting.migration;

import com.inventory.accounting.domain.model.LedgerEntry;
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
 * Idempotent: ensures {@code shop_account_time} {@code {shopId, accountId, postedAt: -1}} on
 * {@code ledger_entries}. Posting a journal reads each account's newest ledger row
 * ({@code AccountingPostingService.latestBalanceFor}); without this index that read scans every row
 * of the account. Automatic index creation is off, so the {@code @CompoundIndex} on
 * {@link LedgerEntry} does nothing by itself.
 */
@Component
@Slf4j
public class LedgerIndexMigration {

  static final String INDEX = "shop_account_time";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(7)
  public void run() {
    try {
      mongoTemplate.indexOps(LedgerEntry.class).ensureIndex(
          new Index()
              .on("shopId", Sort.Direction.ASC)
              .on("accountId", Sort.Direction.ASC)
              .on("postedAt", Sort.Direction.DESC)
              .named(INDEX));
      log.info("[ledger-index] ensured {}", INDEX);
    } catch (RuntimeException e) {
      log.error("[ledger-index] failed to ensure {}: {}", INDEX, e.getMessage(), e);
    }
  }
}
