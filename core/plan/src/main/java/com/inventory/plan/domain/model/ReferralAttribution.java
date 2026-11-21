package com.inventory.plan.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Who referred a shop. One per referee (unique index), written once at shop registration. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "referral_attributions")
public class ReferralAttribution {

  @Id
  private String id;
  private String refereeShopId;
  /** Null until resolved: a name alone never identifies a shop. */
  private String referrerShopId;
  private String referrerCodeUsed;
  private String rawReferredByName;
  private ReferralAttributionStatus status;
  /** Set when {@code status} is PENDING_REVIEW. */
  private ReferralReviewReason reviewReason;
  private Instant createdAt;
  private Instant resolvedAt;
  private String resolvedByUserId;
  private String rejectionReason;
}
