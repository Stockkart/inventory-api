package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReferralAttributionRepository extends MongoRepository<ReferralAttribution, String> {

  Optional<ReferralAttribution> findByRefereeShopId(String refereeShopId);

  long countByReferrerShopId(String referrerShopId);

  long countByReferrerShopIdAndStatus(String referrerShopId, ReferralAttributionStatus status);
}
