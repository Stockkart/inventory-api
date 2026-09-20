package com.inventory.plugins.cafe.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One punch of a round, and the idempotency claim for it.
 *
 * <p>This document is inserted <em>before</em> any ticket exists: the unique index on
 * {@code (shopId, idempotencyKey)} makes that insert the claim. There are no MongoDB transactions
 * here, so the claim is what lets a retry tell "this punch already finished" from "this punch was
 * claimed but died mid-flight" — a state a key stamped on the tickets themselves could not express.
 */
@Data
@Document(collection = "cafe_order_punches")
@CompoundIndexes({
  @CompoundIndex(
      name = "shop_idempotency_unique",
      def = "{'shopId': 1, 'idempotencyKey': 1}",
      unique = true),
  @CompoundIndex(name = "shop_order", def = "{'shopId': 1, 'orderId': 1}")
})
public class CafeOrderPunch {

  @Id private String id;

  @Indexed private String shopId;

  private String orderId;
  private String idempotencyKey;

  /** Allocated once, here — never by counting tickets, which would race between punches. */
  private Integer roundNo;

  private List<String> kotIds = new ArrayList<>();
  private CafePunchStatus status;
  private Instant createdAt;
  private String createdBy;
}
