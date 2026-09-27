package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReferralAttributionRepository extends MongoRepository<ReferralAttribution, String> {

  Optional<ReferralAttribution> findByRefereeShopId(String refereeShopId);

  long countByReferrerShopId(String referrerShopId);

  List<ReferralAttribution> findByStatusOrderByCreatedAtAsc(ReferralAttributionStatus status, Pageable page);

  long countByReferrerShopIdAndStatus(String referrerShopId, ReferralAttributionStatus status);
}
