package com.inventory.plugins.cafe.domain;

import java.util.List;
import lombok.Data;

/**
 * The crash-recovery log for one flush in flight, written by the claim ({@code findAndModify})
 * that empties {@link CafeTab#getLines()}. Populated starting Task 3; left {@code null} by the
 * tab lifecycle itself — a tab with no pending flush owes the kitchen nothing.
 */
@Data
public class CafePendingFlush {

  private String flushId;
  private String idempotencyKey;
  private List<CafeTabLine> lines;
  private String targetPurchaseId;
  private CafeFlushStatus status;
}
