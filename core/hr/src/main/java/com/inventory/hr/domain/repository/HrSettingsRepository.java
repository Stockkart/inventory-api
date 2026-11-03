package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.HrSettings;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HrSettingsRepository extends MongoRepository<HrSettings, String> {

  Optional<HrSettings> findByShopId(String shopId);

  boolean existsByShopId(String shopId);
}
