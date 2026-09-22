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
 * One printed kitchen ticket: the immutable kitchen snapshot.
 *
 * <p>Lines are snapshotted rather than referenced because a reprint must reproduce what the kitchen
 * actually received, even after later rounds or a void changed the order.
 */
@Data
@Document(collection = "cafe_kots")
// One index, and it is the one scripts/cafe-kot-indexes.mongodb.js creates. shopId needs none
// of its own: the two queries on this collection are findByIdAndShopId, served by _id, and
// findByShopIdAndFlushId, served by this -- of which shopId is the prefix.
@CompoundIndexes({
  @CompoundIndex(name = "shop_flush", def = "{'shopId': 1, 'flushId': 1}")
})
public class CafeKot {

  @Id private String id;

  private String shopId;

  private String purchaseId;
  private Integer kotNo;
  private String department;

  /**
   * Whether this ticket sends food or stops it. Null on tickets written by the running-order path,
   * which are all ISSUE by construction.
   *
   * <p>This is the cafe-side carrier of the {@code KotStamp} distinction: {@code KotStamp} lives in
   * {@code core/documentservice}, which this module does not depend on, so the printing side maps
   * {@link CafeKotKind#CANCEL} to {@code KotStamp.CANCELLED}.
   */
  private CafeKotKind kind;

  private Integer roundNo;
  private CafeKotStatus status;

  /** Dine-in table the cart was punched for, frozen from the purchase. Free text; no registry. */
  private String tableLabel;

  /** Daily order token the cart was punched for, frozen from the purchase. */
  private String tokenNo;

  private List<CafeKotLine> lines = new ArrayList<>();
  private Integer reprintCount = 0;

  /**
   * The flush that produced this ticket. Idempotency lives on the flush record written onto the
   * tab, never here: one flush creates one ticket per department, so a unique key on this document
   * could not be unique across them.
   *
   * <p>It is also the first segment of the ticket's {@code _id}
   * ({@code {flushId}:{department}:{kind}}), which is what makes ticket creation idempotent by
   * construction — and what lets a recovery ask the repository which tickets this flush already
   * wrote <b>before</b> it allocates a single {@code kotNo}.
   */
  private String flushId;

  private String businessDate;
  private Instant createdAt;
  private String createdBy;
}
