package com.inventory.accounting.migration;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.utils.constants.AccountingMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Idempotent: ensures {@code shop_source_unique} {@code {shopId, sourceType, sourceId}} UNIQUE on
 * {@code journal_entries}, partial on {@code sourceId} being a string. Posting is "read, then
 * insert" ({@code AccountingPostingService.post}); this index makes the insert of a second entry
 * for the same source fail, so two concurrent posts of one event leave one entry (the loser gets
 * the existing one back). Partial, not sparse: entries without a source ({@code MANUAL}, null
 * {@code sourceId}) still have {@code shopId} and {@code sourceType}, so a sparse compound index
 * would make them collide. Automatic index creation is off, so the {@code @CompoundIndex} on
 * {@link JournalEntry} does nothing by itself.
 *
 * <p>Old data may already hold duplicates; then Mongo refuses the index. That must not stop the
 * app: the failure is logged as a warning with counts only (no shop ids, amounts or sources),
 * counted in {@code inventory_accounting_index_failures_total}, and posting keeps today's
 * read-then-insert behaviour until the duplicates are cleaned up and the app restarts.
 *
 * <p>{@code coa_accounts (shopId, code)} is deliberately not made unique here: the seeder inserts
 * missing codes with read-then-{@code saveAll}, so a unique index would turn a concurrent first
 * seed into a posting error; that needs the seeder to handle duplicates first.
 */
@Component
@Slf4j
public class JournalSourceIndexMigration {

  static final String INDEX = "shop_source_unique";

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private MetricsWrapper metrics;

  @EventListener(ApplicationReadyEvent.class)
  @Order(13)
  public void run() {
    try {
      mongoTemplate.indexOps(JournalEntry.class).ensureIndex(index());
      log.info("[journal-index] ensured {}", INDEX);
    } catch (RuntimeException e) {
      // never fail start-up from here: the reporting itself is guarded too
      try {
        metrics.increment(AccountingMetricsConstants.INDEX_FAILURES_TOTAL, 1,
            "module", AccountingMetricsConstants.MODULE, "index", INDEX);
        // counts only: the duplicate keys themselves name shops and business events
        log.warn("[journal-index] could not ensure {} ({}); posting stays read-then-insert. {}",
            INDEX, e.getClass().getSimpleName(), duplicateSummary());
      } catch (RuntimeException reportFailed) {
        log.warn("[journal-index] could not ensure {} ({}) nor report it ({})", INDEX,
            e.getClass().getSimpleName(), reportFailed.getClass().getSimpleName());
      }
    }
  }

  /** Package-private for the test. */
  static Index index() {
    return new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("sourceType", Sort.Direction.ASC)
        .on("sourceId", Sort.Direction.ASC)
        .unique()
        // BSON type 2 = string, i.e. partialFilterExpression {sourceId: {$type: "string"}}
        .partial(PartialIndexFilter.of(Criteria.where("sourceId").type(2)))
        .named(INDEX);
  }

  /** "N duplicated sources (M extra entries)", or why that could not be counted. */
  String duplicateSummary() {
    try {
      List<Document> pipeline = List.of(
          new Document("$match", new Document("sourceId", new Document("$type", "string"))),
          new Document("$group", new Document("_id", new Document("shop", "$shopId")
              .append("type", "$sourceType").append("source", "$sourceId"))
              .append("n", new Document("$sum", 1))),
          new Document("$match", new Document("n", new Document("$gt", 1))),
          new Document("$group", new Document("_id", null)
              .append("keys", new Document("$sum", 1))
              .append("extra", new Document("$sum", new Document("$subtract", List.of("$n", 1))))));
      Document totals = mongoTemplate.getCollection(mongoTemplate.getCollectionName(
          JournalEntry.class)).aggregate(pipeline).allowDiskUse(true)
          // bounded: a large collection must not hold up readiness
          .maxTime(30, java.util.concurrent.TimeUnit.SECONDS).first();
      if (totals == null) {
        return "No duplicated sources found; check the index options against the existing index.";
      }
      return totals.get("keys") + " duplicated source(s), " + totals.get("extra")
          + " extra entr(y/ies); clean them up and restart to create the index.";
    } catch (RuntimeException e) {
      return "Duplicates could not be counted (" + e.getClass().getSimpleName() + ").";
    }
  }
}
