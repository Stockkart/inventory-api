package com.inventory.credit.migration;

import com.inventory.credit.domain.model.CreditAccount;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent: makes {@code {shopId, partyType, partyRefId}} on {@code credit_accounts} unique
 * ({@code shop_party_unique}). A party's first credit entry creates its account with an upsert; two
 * first entries at the same moment both insert unless the index is unique (the second then fails with
 * a duplicate key and is retried onto the first). Automatic index creation is off, so the unique
 * {@code @CompoundIndex} on {@link CreditAccount} never reached the database: databases carry a
 * non-unique index on the same keys, which is replaced here.
 *
 * <p>If any party already has two accounts the unique index cannot be built: nothing is changed and
 * the duplicates are logged, to be merged by hand.
 */
@Component
@Slf4j
public class CreditAccountIndexMigration {

  static final String INDEX = "shop_party_unique";
  static final Document KEYS = new Document("shopId", 1).append("partyType", 1).append("partyRefId", 1);

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(9)
  public void run() {
    try {
      MongoCollection<Document> accounts = mongoTemplate.getCollection(mongoTemplate.getCollectionName(CreditAccount.class));
      Document existing = null;
      for (Document ix : accounts.listIndexes()) {
        if (KEYS.equals(ix.get("key", Document.class))) {
          existing = ix;
        }
      }
      if (existing != null && Boolean.TRUE.equals(existing.getBoolean("unique"))) {
        log.info("[credit-account-index] {} already unique", existing.getString("name"));
        return;
      }
      List<Document> duplicates =
          accounts.aggregate(List.of(
                  new Document("$group", new Document("_id", new Document("shopId", "$shopId")
                          .append("partyType", "$partyType").append("partyRefId", "$partyRefId"))
                      .append("accounts", new Document("$sum", 1))),
                  new Document("$match", new Document("accounts", new Document("$gt", 1))),
                  new Document("$limit", 20)))
              .into(new java.util.ArrayList<>());
      if (!duplicates.isEmpty()) {
        log.error(
            "[credit-account-index] not made unique: {} part(y/ies) have more than one credit account, e.g. {}",
            duplicates.size(), duplicates.get(0).get("_id"));
        return;
      }
      if (existing != null) {
        accounts.dropIndex(existing.getString("name")); // same keys, not unique: replaced below
      }
      accounts.createIndex(KEYS, new IndexOptions().unique(true).name(INDEX));
      log.info("[credit-account-index] ensured unique {}", INDEX);
    } catch (RuntimeException e) {
      log.error("[credit-account-index] failed to ensure {}: {}", INDEX, e.getMessage(), e);
    }
  }
}
