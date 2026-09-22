package com.inventory.plugins.cafe;

import com.inventory.plugins.cafe.domain.CafeFlushStatus;
import com.inventory.plugins.cafe.domain.CafePendingFlush;
import com.inventory.plugins.cafe.domain.CafeTab;
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
 * Claims a tab's unsent lines for one flush — in exactly one write.
 *
 * <p>This is the serialisation point of the whole feature. Two cashiers pressing at once, or one
 * cashier pressing twice, and exactly one claim wins: the loser's {@code findAndModify} matches
 * nothing and changes nothing. There is no {@code MongoTransactionManager} in this application, so
 * the claim cannot be one half of a transaction with the append that follows it; instead it writes
 * its own recovery log. Emptying {@link CafeTab#getLines()} and recording
 * {@link CafePendingFlush} are therefore a single update and never two — a crash between them
 * would empty a tab while losing forever the lines the kitchen is owed, with nothing in the system
 * aware of it.
 *
 * <p><b>Idempotency is the {@code $ne} clause in the query, not an index.</b> A unique index cannot
 * do this job: {@code pendingFlush} lives inside the tab document, and a unique <i>multikey</i>
 * index permits duplicate values within a single document's array, so it would not stop a second
 * claim of the same tab. The query clause is what stops it.
 *
 * <p>{@code returnNew(false)} is equally load-bearing. The update's whole purpose is to empty the
 * lines, so the post-image has nothing left to claim: the <b>pre-image</b> is what carries the
 * claimed lines to the kitchen. {@code upsert(false)} because a claim must never invent a tab —
 * an unknown id is a caller error, not a new party.
 */
@Service
@Slf4j
public class CafeTabFlusher {

  static final String COLLECTION = "cafe_tabs";

  /**
   * How many recent idempotency keys a tab remembers. Enough to cover a client whose parked key
   * is a round or two behind; small enough that a tab open all evening does not grow an audit
   * log. The array is trimmed by the claim itself, with {@code $slice}, so it is bounded by the
   * write rather than by anybody remembering to prune it.
   */
  static final int RECENT_FLUSH_KEYS_KEPT = 10;

  private final MongoTemplate mongoTemplate;

  public CafeTabFlusher(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Claims the tab's lines for this flush.
   *
   * @return the tab's <b>pre-image</b> — the lines as they were before the claim emptied them — or
   *     {@link Optional#empty()} when nothing matched, which means <i>either</i> no such tab for
   *     this shop and cashier <i>or</i> this idempotency key has already claimed. The caller must
   *     re-read the tab to tell those two apart; an empty result alone cannot.
   */
  public Optional<CafeTab> claim(
      String shopId,
      String userId,
      String tabId,
      String flushId,
      String idempotencyKey,
      String targetPurchaseId) {

    Query query =
        Query.query(
            Criteria.where("_id")
                .is(tabId)
                .and("shopId")
                .is(shopId)
                .and("userId")
                .is(userId)
                // The idempotency. Not an index: see the class javadoc.
                .and("pendingFlush.idempotencyKey")
                .ne(idempotencyKey)
                // A tab that still owes an earlier flush is never claimed out from under it.
                // The $ne above excludes only THIS key, so a different key would otherwise match
                // a tab carrying somebody else's PENDING record and overwrite it — taking an
                // empty pre-image and erasing the only evidence those lines are owed to a
                // kitchen that has not been told. The caller finishes the older flush first;
                // this clause is what makes that check hold across the gap between its read and
                // its claim. $ne also matches a document with no pendingFlush at all, which is
                // the ordinary case.
                .and("pendingFlush.status")
                .ne(CafeFlushStatus.PENDING.name())
                // A key this tab has already been claimed under never claims again, however far
                // back it was — pendingFlush remembers only the latest. See CafeRecentFlush.
                .and("recentFlushKeys.idempotencyKey")
                .nin(idempotencyKey));

    // A two-stage aggregation-pipeline update, because stage 1 has to read a field of the very
    // document it is updating. Pipeline stages run in sequence, so the order below is
    // load-bearing: reversed, the recovery log would capture the emptied array and the claimed
    // lines would be lost with nothing in the system aware of it.
    AggregationUpdate update =
        AggregationUpdate.from(
            List.of(
                // Stage 1 FIRST, while `lines` still holds what the kitchen is owed.
                stage(recordPendingFlushStage(flushId, idempotencyKey, targetPurchaseId)),
                // Stage 2 second: only now may the tab be emptied.
                stage(emptyLinesStage(flushId, idempotencyKey))));

    CafeTab preImage =
        mongoTemplate.findAndModify(
            query,
            update,
            // returnNew(false): the post-image has no lines left to claim.
            FindAndModifyOptions.options().returnNew(false).upsert(false),
            CafeTab.class,
            COLLECTION);

    if (preImage == null) {
      log.debug(
          "Cafe flush {} did not claim tab {} in shop {} (already claimed with key {}, or absent)",
          flushId,
          tabId,
          shopId,
          idempotencyKey);
      return Optional.empty();
    }
    return Optional.of(preImage);
  }

  /** A raw pipeline stage; building these by hand beats fighting the fluent builder. */
  private static AggregationOperation stage(Document raw) {
    return context -> raw;
  }

  /**
   * Stage 1 — write the recovery log, its {@code lines} copied from the tab as it stands right
   * now. {@code $ifNull} so a tab whose array is absent claims as cleanly as one whose is empty.
   *
   * <p>The status is the literal name of {@link CafeFlushStatus#PENDING}: a pipeline stage is a
   * raw BSON document and never passes through the mapping layer that would convert the enum.
   */
  private static Document recordPendingFlushStage(
      String flushId, String idempotencyKey, String targetPurchaseId) {
    Document pendingFlush =
        new Document("flushId", flushId)
            .append("idempotencyKey", idempotencyKey)
            .append("targetPurchaseId", targetPurchaseId)
            .append("status", CafeFlushStatus.PENDING.name())
            .append("lines", new Document("$ifNull", List.of("$lines", List.of())));
    return new Document("$set", new Document("pendingFlush", pendingFlush));
  }

  /**
   * Stage 2 — the tab holds only unsent items, and these have just been claimed.
   *
   * <p>The same stage remembers the key, because the claim is the only moment at which a key is
   * known to have won: appending it to {@code recentFlushKeys} here means a stale retry of it is
   * refused by the query above however many flushes later it arrives. {@code $slice} with a
   * negative count keeps the newest {@link #RECENT_FLUSH_KEYS_KEPT} and drops the rest, so the
   * write bounds the array rather than a cleanup job.
   */
  private static Document emptyLinesStage(String flushId, String idempotencyKey) {
    Document entry = new Document("idempotencyKey", idempotencyKey).append("flushId", flushId);
    Document appended =
        new Document(
            "$concatArrays",
            List.of(
                new Document("$ifNull", List.of("$recentFlushKeys", List.of())),
                List.of(entry)));
    return new Document(
        "$set",
        new Document("lines", List.of())
            .append(
                "recentFlushKeys",
                new Document("$slice", List.of(appended, -RECENT_FLUSH_KEYS_KEPT)))
            .append("updatedAt", Date.from(Instant.now())));
  }
}
