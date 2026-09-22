package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.CafeKotCancelStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One cancellation owed to the kitchen for a single bill line, recorded on {@link
 * Purchase#getCafeKotCancels()}.
 *
 * <p>Written by {@code plugins/cafe}'s {@code CafeKotCancelService} as a raw {@code
 * org.bson.Document} — that module cannot depend on {@code core/product} and reaches this array
 * the same way the flush reaches {@code Purchase.items}. This typed mirror exists so the rest of
 * {@code core/product} (the checkout path, in particular) can read it without re-parsing BSON.
 *
 * <p>Idempotency follows the flush's shape exactly: {@link #cancelId} is generated once, before
 * the record is first written PENDING, and is reused verbatim as the produced {@code CafeKot}'s
 * {@code flushId} — which is also the first segment of that ticket's {@code _id}. A crash between
 * this record landing and the ticket being written is recovered by retrying with the same {@link
 * #idempotencyKey}: the record is found, still PENDING, and the same {@link #cancelId} is used
 * again, so rewriting the ticket is a no-op rather than a duplicate.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CafeKotCancel {

  private String cancelId;
  private String idempotencyKey;

  /** The bill line this cancellation targets — {@code PurchaseItem.sellableRef}. */
  private String lineRef;

  /** Absolute quantity owed to the kitchen as a cancellation. Never negative. */
  private Integer quantity;

  /** The station frozen onto the line at compose time — never re-resolved. */
  private String department;

  private String note;
  private String name;

  private CafeKotCancelStatus status;

  private String createdBy;
  private Instant createdAt;
}
