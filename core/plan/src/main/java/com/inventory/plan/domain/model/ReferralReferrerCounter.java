package com.inventory.plan.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One row per referrer shop, bumped in the same transaction as each new referral reward. Two
 * rewards for the same referrer recorded at once both write this row, so MongoDB lets only one of
 * them commit and the other runs again and sees the first: the per-referrer cap cannot be passed
 * by a race.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "referral_referrer_counters")
public class ReferralReferrerCounter {

  /** The referrer shop id. */
  @Id
  private String referrerShopId;
  /** Rewards recorded for this referrer, ever (voided ones included). Informational. */
  private long rewardsRecorded;
  private Instant updatedAt;
}
