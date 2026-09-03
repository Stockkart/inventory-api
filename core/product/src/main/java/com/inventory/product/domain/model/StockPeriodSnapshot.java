package com.inventory.product.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A frozen company-wise closing stock value for one shop at one period end.
 *
 * <p>This is what makes the Bank Summary chain: the closing value written here for
 * 31-Jul <em>is</em> the opening value the 01-Aug report reads. Without it, opening
 * has to be reconstructed by rolling live counters backwards over every movement,
 * which is deterministic but re-derived on every request and silently wrong if a lot
 * was ever hard-deleted.
 *
 * <p>Keyed by company name rather than by lot, because that is the grain the report
 * prints and the grain the carry-forward needs. A per-lot snapshot would be a
 * different, larger document and is not required until an item-level report exists.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stock_period_snapshots")
@CompoundIndexes({
    @CompoundIndex(
        name = "shop_period_end_unique_idx",
        def = "{'shopId': 1, 'periodEnd': 1}",
        unique = true)
})
public class StockPeriodSnapshot {

  @Id
  private String id;

  private String shopId;

  /** Last day of the closed period, inclusive. The next period opens from this document. */
  private LocalDate periodEnd;

  /** Company name to closing stock value at cost. Insertion-ordered for stable exports. */
  @Builder.Default
  private Map<String, BigDecimal> closingByCompany = new LinkedHashMap<>();

  /** Sum of {@link #closingByCompany}, stored so a total never has to be re-added. */
  private BigDecimal totalClosing;

  private Instant createdAt;

  private String createdByUserId;
}
