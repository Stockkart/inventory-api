package com.inventory.plugins.cafe;

import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Writes a {@link CafeTab} the way {@code PurchaseTargetedWriter} writes a bill — named fields
 * through {@code updateFirst}, never a full-document replace.
 *
 * <p><b>Why.</b> {@code CafeTabRepository.save(tab)} replaces the whole document with a snapshot
 * this request read a moment ago, and the tab document is not only the cashier's unsent lines: it
 * also holds {@link CafeTab#getPendingFlush()} and {@link CafeTab#getRecentFlushKeys()}, which
 * {@link CafeTabFlusher}'s claim writes and which are the <i>only</i> record that a round has been
 * taken off the tab and is owed to a kitchen that has not been told yet.
 *
 * <p>The sequence that made this fatal: the cashier presses Print KOT; the claim empties
 * {@code lines} and records the PENDING flush; during the hundreds of milliseconds of append,
 * totals and ticket round-trips that follow, the cashier composes the next item. That edit read
 * the tab <i>before</i> the claim and saves it <i>after</i>, so the replace restores the claimed
 * lines, nulls {@code pendingFlush} and rewinds {@code recentFlushKeys}. The flush's
 * {@code markComplete} then matches nothing and only warns — and the claimed round is back on the
 * tab, so the next Print KOT sends the same food to the kitchen a second time, under a fresh
 * flush id that {@code recentFlushKeys} no longer remembers refusing.
 *
 * <p><b>How.</b> Each operation names what it changes and nothing else: {@code $push} a composed
 * line, {@code $set} one stored line's quantity and note through an array filter on its
 * {@code lineRef}, {@code $pull} a line by {@code lineRef}, {@code $set} the status. A field this
 * request did not touch is not in the update at all, so the claim's record survives every one of
 * them. There is no {@code MongoTransactionManager} in this codebase, so there is nothing to make
 * a read-modify-replace atomic; a targeted write needs no atomicity, because it cannot delete
 * what it does not name.
 *
 * <p>Every write is scoped by {@code shopId} <i>and</i> {@code userId}, as every tab read is, and
 * additionally requires the tab to still be OPEN at the moment of the write rather than merely at
 * the moment it was read.
 */
@Component
@Slf4j
public class CafeTabTargetedWriter {

  private static final String LINES = "lines";
  private static final String LINE_REF = "lineRef";
  private static final String UPDATED_AT = "updatedAt";
  private static final String STATUS = "status";

  private final MongoTemplate mongoTemplate;

  public CafeTabTargetedWriter(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /** Appends one composed line. Never touches the lines already there, nor the flush record. */
  public long appendLine(String shopId, String userId, String tabId, CafeTabLine line) {
    return apply(
        shopId, userId, tabId, new Update().push(LINES, line).set(UPDATED_AT, Instant.now()));
  }

  /**
   * Changes one stored line's quantity and/or note, addressed by its {@code lineRef}.
   *
   * <p>{@code quantity} null leaves the quantity alone, and {@code changeNote} false leaves the
   * note alone — the service's own contract, kept here rather than inferred from a null note,
   * because a normalized blank note <i>is</i> null and means "clear it". The frozen department,
   * price and tax rates are never in the update at all.
   */
  public long updateLine(
      String shopId,
      String userId,
      String tabId,
      String lineRef,
      Integer quantity,
      boolean changeNote,
      String normalizedNote) {
    Update update = new Update().set(UPDATED_AT, Instant.now());
    if (quantity != null) {
      update.set(LINES + ".$[l].quantity", quantity);
    }
    if (changeNote) {
      if (normalizedNote == null) {
        // What the full replace did to a field that became null.
        update.unset(LINES + ".$[l].note");
      } else {
        update.set(LINES + ".$[l].note", normalizedNote);
      }
    }
    update.filterArray(Criteria.where("l." + LINE_REF).is(lineRef));
    return apply(shopId, userId, tabId, update);
  }

  /** Removes one line by {@code lineRef}, and only that element of the array. */
  public long removeLine(String shopId, String userId, String tabId, String lineRef) {
    return apply(
        shopId,
        userId,
        tabId,
        new Update()
            .pull(LINES, new Document(LINE_REF, lineRef))
            .set(UPDATED_AT, Instant.now()));
  }

  /** The one field a close changes. A tab that still owes the kitchen keeps its record. */
  public long close(String shopId, String userId, String tabId) {
    return apply(
        shopId,
        userId,
        tabId,
        new Update().set(STATUS, CafeTabStatus.CLOSED).set(UPDATED_AT, Instant.now()));
  }

  /**
   * Scoped by shop and cashier, and still OPEN at the moment of the write.
   *
   * @return the matched count — 0 means the tab is gone, belongs to somebody else, or was closed
   *     between the read and this write.
   */
  private long apply(String shopId, String userId, String tabId, Update update) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(tabId)
                .and("shopId")
                .is(shopId)
                .and("userId")
                .is(userId)
                .and(STATUS)
                .is(CafeTabStatus.OPEN));
    return mongoTemplate
        .updateFirst(query, update, CafeTab.class, CafeTabFlusher.COLLECTION)
        .getMatchedCount();
  }
}
