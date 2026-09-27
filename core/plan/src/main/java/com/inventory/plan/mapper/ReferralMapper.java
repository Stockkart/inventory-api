package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.rest.dto.response.ReferralCodeCheckResponse;
import com.inventory.plan.rest.dto.response.ReferralSummaryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReferralMapper {

  default ReferralCodeCheckResponse toCheckResponse(boolean valid, String displayName) {
    return ReferralCodeCheckResponse.builder().valid(valid).displayName(valid ? displayName : null).build();
  }

  default ReferralSummaryResponse toSummary(
      String referralCode, long resolved, long pendingReview, ReferralAttribution referredBy) {
    return ReferralSummaryResponse.builder()
        .referralCode(referralCode)
        .resolvedReferrals(resolved)
        .pendingReviewReferrals(pendingReview)
        .referredByStatus(referredBy == null ? null : referredBy.getStatus())
        .build();
  }
}
