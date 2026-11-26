package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.CampaignTheme;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CampaignThemeRepository extends MongoRepository<CampaignTheme, String> {

  Optional<CampaignTheme> findByCode(String code);
}
