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
 * One printed kitchen ticket: the immutable kitchen snapshot.
 *
 * <p>Lines are snapshotted rather than referenced because a reprint must reproduce what the kitchen
 * actually received, even after later rounds or a void changed the order.
 */
@Data
@Document(collection = "cafe_kots")
@CompoundIndexes({
  @CompoundIndex(name = "shop_order", def = "{'shopId': 1, 'orderId': 1}"),
  @CompoundIndex(name = "shop_punch", def = "{'shopId': 1, 'punchId': 1}")
})
public class CafeKot {

  @Id private String id;

  @Indexed private String shopId;

  private String orderId;
  private Integer kotNo;
  private String department;
  private Integer roundNo;
  private CafeKotStatus status;
  private List<CafeKotLine> lines = new ArrayList<>();
  private String voidReason;
  private String voidedBy;
  private Instant voidedAt;
  private Integer reprintCount = 0;

  /**
   * The punch that produced this ticket. Idempotency lives on the punch, never here: one punch
   * creates one ticket per department, so a key on this document could not be unique across them.
   */
  private String punchId;

  private String businessDate;
  private Instant createdAt;
  private String createdBy;
}
