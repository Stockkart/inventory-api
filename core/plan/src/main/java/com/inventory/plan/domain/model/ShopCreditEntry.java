package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One wallet movement. Deltas and balances-after come from the same compare-and-set that changed the
 * wallet, so the ledger always reconciles with it. Unique per {@code referenceId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_credit_entries")
public class ShopCreditEntry {

  @Id
  private String id;
  private String shopId;
  private String referenceId;
  private ShopCreditSource source;
  /** Order, reward or adjustment this movement belongs to. */
  private String sourceId;
  private BigDecimal amount;
  private BigDecimal availableDelta;
  private BigDecimal reservedDelta;
  private BigDecimal outstandingDelta;
  private BigDecimal availableAfter;
  private BigDecimal reservedAfter;
  private BigDecimal outstandingAfter;
  private String note;
  private String createdByUserId;
  private Instant createdAt;
}
