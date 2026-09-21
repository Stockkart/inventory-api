package com.inventory.plugins.cafe;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Claims a cafe cart for a KOT punch and reconciles its lines — in exactly one write.
 *
 * <p>There is no {@code MongoTransactionManager} in this application: no JPA or JDBC starter, no
 * transaction manager bean, and the {@code @Transactional} annotations elsewhere have nothing
 * behind them. So the punch record and the line advance it caused CANNOT be two writes. A crash
 * between them would advance the cart while losing the deltas forever: every retry would then
 * compute zeros, and food already cooking would never be ticketed or billed, with nothing in the
 * system aware of it.
 *
 * <p>Everything therefore happens in a single {@code findAndModify} whose update is a two-stage
 * aggregation pipeline:
 *
 * <ol>
 *   <li>append a punch to {@code cafeKotPunches}, with deltas computed from the lines <b>as they
 *       still are</b>;
 *   <li>set every line's {@code kotPunchedQuantity} to its own {@code baseQuantity}, dropping the
 *       lines that end up empty on both counts.
 * </ol>
 *
 * <p>Pipeline stages run in sequence, so the order above is load-bearing. Reversed, stage 1 would
 * read lines that are already reconciled and every recorded delta would be zero — the kitchen
 * would silently receive nothing.
 *
 * <p>Idempotency is the {@code $ne} clause in the query, not an index. A unique multikey index on
 * {@code cafeKotPunches.idempotencyKey} would not help: MongoDB permits duplicate values inside a
 * single document's array, so it would not stop a second append to the same cart.
 */
@Service
@Slf4j
public class CafeCartPuncher {

  private static final String COLLECTION = "purchases";

  /** {@code CafeKotPunchStatus.PENDING_KOT_CREATION}; a literal here — cafe does not see core. */
  private static final String PENDING_KOT_CREATION = "PENDING_KOT_CREATION";

  private final MongoTemplate mongoTemplate;

  public CafeCartPuncher(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Appends the punch and reconciles the lines atomically.
   *
   * @return the purchase's <b>pre-image</b> — the lines as they were before reconciliation — or
   *     {@link Optional#empty()} when nothing matched, which means either no such cart in this
   *     shop or this idempotency key has already been punched.
   */
  public Optional<Document> claimAndReconcile(
      String shopId, String purchaseId, String punchId, String idempotencyKey, String userId) {

    Query query =
        Query.query(
            Criteria.where("_id")
                .is(purchaseId)
                .and("shopId")
                .is(shopId)
                // The idempotency. Not an index: see the class javadoc.
                .and("cafeKotPunches.idempotencyKey")
                .ne(idempotencyKey));

    AggregationUpdate update =
        AggregationUpdate.from(
            List.of(
                // Stage 1 FIRST, while the lines still differ from their punched counts.
                stage(appendPunchStage(punchId, idempotencyKey, userId)),
                // Stage 2 second: only now may the lines advance.
                stage(reconcileLinesStage())));

    Document preImage =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(false).upsert(false),
            Document.class,
            COLLECTION);

    if (preImage == null) {
      log.debug(
          "Cafe punch {} did not match purchase {} in shop {} (already punched, or absent)",
          punchId,
          purchaseId,
          shopId);
      return Optional.empty();
    }
    return Optional.of(preImage);
  }

  /** A raw pipeline stage; building these by hand beats fighting the fluent builder. */
  private static AggregationOperation stage(Document raw) {
    return context -> raw;
  }

  /**
   * Stage 1 — append one punch, its deltas read off the lines as they stand right now.
   *
   * <p>{@code $concatArrays} onto {@code $ifNull: ["$cafeKotPunches", []]} so a cart that has
   * never been punched (field absent) appends just as cleanly as one that has.
   */
  private static Document appendPunchStage(String punchId, String idempotencyKey, String userId) {
    Document punch =
        new Document("punchId", punchId)
            .append("idempotencyKey", idempotencyKey)
            .append("status", PENDING_KOT_CREATION)
            .append("createdAt", Date.from(Instant.now()))
            .append("createdBy", userId)
            // Filled in by ticket creation, once the KOTs exist.
            .append("kotIds", List.of())
            .append("deltas", deltasExpression());

    return new Document(
        "$set",
        new Document(
            "cafeKotPunches",
            new Document(
                "$concatArrays",
                List.of(
                    new Document("$ifNull", List.of("$cafeKotPunches", List.of())),
                    List.of(punch)))));
  }

  /**
   * Per line: {@code baseQuantity - kotPunchedQuantity}, null-safe on both sides, keeping only the
   * lines that actually moved. A negative quantity is a cancellation of that many.
   *
   * <p>{@code sellableRef}, {@code name}, {@code department} and {@code note} ride along so ticket
   * creation needs no second lookup — and so the ticket records the station as it was frozen onto
   * the line, not as some later edit of the menu would resolve it.
   */
  private static Document deltasExpression() {
    Document delta =
        new Document("sellableRef", "$$line.sellableRef")
            .append("name", "$$line.name")
            .append("department", "$$line.department")
            .append("note", "$$line.note")
            .append(
                "quantity",
                new Document(
                    "$subtract",
                    List.of(
                        new Document("$ifNull", List.of("$$line.baseQuantity", 0)),
                        new Document("$ifNull", List.of("$$line.kotPunchedQuantity", 0)))));

    return new Document(
        "$filter",
        new Document(
                "input",
                new Document(
                    "$map",
                    new Document("input", new Document("$ifNull", List.of("$items", List.of())))
                        .append("as", "line")
                        .append("in", delta)))
            .append("as", "delta")
            .append("cond", new Document("$ne", List.of("$$delta.quantity", 0))));
  }

  /**
   * Stage 2 — every line's {@code kotPunchedQuantity} becomes its own {@code baseQuantity}, then
   * the lines sitting at zero on both counts are dropped.
   *
   * <p>{@code $mergeObjects} rather than a rebuilt document, so no field of a line is lost to this
   * write. The drop runs after the merge: a line the guest cancelled down to zero was kept alive
   * only so this punch could compute its negative delta, and that delta is already recorded by
   * stage 1.
   */
  private static Document reconcileLinesStage() {
    Document reconciledLine =
        new Document(
            "$mergeObjects",
            List.of(
                "$$line",
                new Document(
                    "kotPunchedQuantity",
                    new Document("$ifNull", List.of("$$line.baseQuantity", 0)))));

    Document advanced =
        new Document(
            "$map",
            new Document("input", new Document("$ifNull", List.of("$items", List.of())))
                .append("as", "line")
                .append("in", reconciledLine));

    Document keep =
        new Document(
            "$not",
            List.of(
                new Document(
                    "$and",
                    List.of(
                        new Document(
                            "$eq",
                            List.of(
                                new Document("$ifNull", List.of("$$line.baseQuantity", 0)), 0)),
                        new Document(
                            "$eq",
                            List.of(
                                new Document("$ifNull", List.of("$$line.kotPunchedQuantity", 0)),
                                0))))));

    return new Document(
        "$set",
        new Document(
            "items",
            new Document(
                "$filter",
                new Document("input", advanced).append("as", "line").append("cond", keep))));
  }
}
