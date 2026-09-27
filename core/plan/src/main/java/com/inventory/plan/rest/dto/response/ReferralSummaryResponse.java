package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.ReferralAttributionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The shop's own referral code and how its referrals stand. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralSummaryResponse {
  private String referralCode;
  private long resolvedReferrals;
  private long pendingReviewReferrals;
  /** How this shop's own attribution stands; null when nobody referred it. */
  private ReferralAttributionStatus referredByStatus;
}
