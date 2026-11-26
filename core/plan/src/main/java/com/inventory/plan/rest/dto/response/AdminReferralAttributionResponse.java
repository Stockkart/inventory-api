package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.ReferralAttributionStatus;
import com.inventory.plan.domain.model.ReferralReviewReason;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminReferralAttributionResponse {
  private String id;
  private String refereeShopId;
  private String refereeShopName;
  private String referrerShopId;
  private String referrerShopName;
  private String referrerCodeUsed;
  private String rawReferredByName;
  private ReferralAttributionStatus status;
  private ReferralReviewReason reviewReason;
  private Instant createdAt;
  private Instant resolvedAt;
  private String resolvedByUserId;
  private String rejectionReason;
}
