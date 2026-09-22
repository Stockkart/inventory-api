package com.inventory.plugins.cafe.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A party's pending order: everything composed on the KOT screen that has not yet been sent to
 * the kitchen. {@link #lines} holds only unsent items — there is no delta anywhere in this
 * feature because a tab never holds what the kitchen already has. Printing (a later task) claims
 * the lines, appends them to a bill, and empties the tab; the tab keeps its {@link #tokenNo}.
 *
 * <p>Scoped to the cashier who opened it: every read and write is additionally filtered by
 * {@code userId}, mirroring {@code QuotationService}'s open-quotation rules exactly. There is no
 * expiry, no TTL and no scheduled cleanup — a tab opened yesterday is still open today.
 */
@Data
@Document(collection = "cafe_tabs")
@CompoundIndexes({
  @CompoundIndex(name = "shop_user_status", def = "{'shopId': 1, 'userId': 1, 'status': 1}")
})
public class CafeTab {

  @Id private String id;

  private String shopId;
  private String userId;

  /** Daily per-shop token allocated from {@code CafeTokenService} under a scope distinct from the bill's token. */
  private String tokenNo;

  private CafeTabStatus status;

  private List<CafeTabLine> lines = new ArrayList<>();

  /** Null until a flush claims this tab's lines (Task 3). */
  private CafePendingFlush pendingFlush;

  /**
   * The last few idempotency keys this tab has been claimed under, newest last, each with the
   * flush it produced. Written by the claim; see {@link CafeRecentFlush} for why the single key
   * on {@link #pendingFlush} is not enough.
   */
  private List<CafeRecentFlush> recentFlushKeys = new ArrayList<>();

  private Instant createdAt;
  private Instant updatedAt;
}
