package com.inventory.app.migration;

import com.inventory.common.util.TxnIdGenerator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-off, idempotent backfill giving every pre-existing vendor MIS document a business
 * {@code txnId}. Runs on startup only when {@code stockkart.txn-id-backfill.enabled=true}, and
 * defaults to {@code dry-run=true}, which logs what would change without writing.
 *
 * <p>Writes go through {@link MongoTemplate} / {@link BulkOperations}, which bypass Spring Data
 * entity conversion — the {@code BeforeConvertCallback} does NOT fire here, so this class generates
 * the UUIDs itself. Each document needs its own value, so this cannot be a single multi-document
 * update.
 *
 * <p>Safe to re-run: documents that already have a {@code txnId} are never modified, so a crash
 * mid-run simply resumes on the next run.
 */
@Component
@Slf4j
public class TxnIdBackfillRunner {

  private static final List<String> COLLECTIONS =
      List.of("vendor_purchase_invoices", "vendor_purchase_returns", "credit_entries");

  private final MongoTemplate mongoTemplate;
  private final int batchSize;

  @Value("${stockkart.txn-id-backfill.enabled:false}")
  private boolean enabled;

  @Value("${stockkart.txn-id-backfill.dry-run:true}")
  private boolean dryRun;

  public TxnIdBackfillRunner(
      MongoTemplate mongoTemplate,
      @Value("${stockkart.txn-id-backfill.batch-size:500}") int batchSize) {
    this.mongoTemplate = mongoTemplate;
    this.batchSize = batchSize;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(30)
  public void run() {
    if (!enabled) {
      return;
    }
    for (String collection : COLLECTIONS) {
      int count = backfillCollection(collection, dryRun);
      log.info(
          "[txn-id-backfill] {}: {} document(s) {}",
          collection,
          count,
          dryRun ? "would be updated (dry run)" : "updated");
    }
  }

  /**
   * Assigns a UUID to every document in {@code collection} whose {@code txnId} is missing or null.
   * In MongoDB the query {@code {txnId: null}} matches both cases. Returns the number of documents
   * updated, or that would be updated when {@code dryRun} is true.
   */
  public int backfillCollection(String collection, boolean dryRun) {
    int total = 0;
    while (true) {
      Query query = Query.query(Criteria.where("txnId").is(null));
      query.fields().include("_id");
      query.limit(batchSize);

      List<Document> batch = mongoTemplate.find(query, Document.class, collection);
      if (batch.isEmpty()) {
        return total;
      }
      total += batch.size();

      if (dryRun) {
        // Nothing is written, so the same batch would be found again — stop after counting one pass.
        return total;
      }

      BulkOperations bulk =
          mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Document.class, collection);
      for (Document doc : batch) {
        bulk.updateOne(
            Query.query(Criteria.where("_id").is(doc.get("_id"))),
            new Update().set("txnId", TxnIdGenerator.generate()));
      }
      bulk.execute();
    }
  }
}
