package com.inventory.product.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One press of Print KOT, embedded on the Purchase.
 *
 * <p>It is appended by the same atomic write that advances the lines, so there is never a
 * state where a line's kotPunchedQuantity has moved but the delta that moved it is lost.
 */
@Data
public class CafeKotPunch {
  private String punchId;
  private String idempotencyKey;
  private List<CafeKotPunchDelta> deltas = new ArrayList<>();
  private List<String> kotIds = new ArrayList<>();
  private CafeKotPunchStatus status;
  private Instant createdAt;
  private String createdBy;
}
