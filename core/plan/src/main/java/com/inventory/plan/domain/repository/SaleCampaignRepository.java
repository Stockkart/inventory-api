package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.SaleCampaign;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SaleCampaignRepository extends MongoRepository<SaleCampaign, String> {

  List<SaleCampaign> findByEndsAtAfter(Instant now);

  Optional<SaleCampaign> findByCode(String code);
}
