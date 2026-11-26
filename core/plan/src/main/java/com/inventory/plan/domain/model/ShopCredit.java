package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A shop's referral wallet (§12, r4.3, §27.4). Every change is a compare-and-set on {@code version}
 * plus one ledger entry, so concurrent checkouts can never spend the same credit twice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_credits")
public class ShopCredit {

  @Id
  private String shopId;
  /** Spendable now. Never negative. */
  private BigDecimal availableBalance;
  /** Held by open orders until they are paid (consumed) or end (released). */
  private BigDecimal reservedBalance;
  /** Clawback that could not be taken; settled from the next credit or release before anything else. */
  private BigDecimal outstandingClawback;
  private long version;
  /** Most recent change references, so a retried change is recognised and not applied twice. */
  @Builder.Default
  private List<String> recentReferences = new ArrayList<>();
  private Instant createdAt;
  private Instant updatedAt;
}
