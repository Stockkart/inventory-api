package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.rest.dto.response.ReferralRewardResponse;
import com.inventory.plan.rest.dto.response.ReferralRewardsResponse;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReferralRewardMapper {

  Set<ReferralRewardStatus> PENDING_STATUSES =
      EnumSet.of(ReferralRewardStatus.PENDING, ReferralRewardStatus.APPROVED, ReferralRewardStatus.CREDITING);

  @Mapping(target = "refereeShopName", source = "refereeShopName")
  ReferralRewardResponse toResponse(ReferralReward reward, String refereeShopName);

  default ReferralRewardsResponse toRewardsResponse(List<ReferralReward> rewards, Map<String, String> refereeNames) {
    return ReferralRewardsResponse.builder()
        .pendingAmount(total(rewards, PENDING_STATUSES))
        .creditedAmount(total(rewards, EnumSet.of(ReferralRewardStatus.CREDITED)))
        .rewards(rewards.stream().map(r -> toResponse(r, refereeNames.get(r.getRefereeShopId()))).toList())
        .build();
  }

  private static BigDecimal total(List<ReferralReward> rewards, Set<ReferralRewardStatus> statuses) {
    return rewards.stream()
        .filter(r -> statuses.contains(r.getStatus()))
        .map(ReferralReward::getRewardAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
