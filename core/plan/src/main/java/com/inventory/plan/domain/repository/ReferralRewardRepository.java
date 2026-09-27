package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReferralRewardRepository extends MongoRepository<ReferralReward, String> {

  Optional<ReferralReward> findByOrderId(String orderId);

  List<ReferralReward> findByReferrerShopIdOrderByCreatedAtDesc(String referrerShopId, Pageable page);

  long countByReferrerShopIdAndStatusInAndCreatedAtAfter(
      String referrerShopId, Collection<ReferralRewardStatus> statuses, Instant after);

  List<ReferralReward> findByStatusInAndHoldUntilLessThanEqual(
      Collection<ReferralRewardStatus> statuses, Instant holdUntil, Pageable page);

  List<ReferralReward> findByStatusAndUpdatedAtBefore(ReferralRewardStatus status, Instant before, Pageable page);
}
