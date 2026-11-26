package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.AppHealthAlert;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Signature-failure alerts ({@code hr_app_alerts}); index {@code (shopId, clearedAt)}. */
@Repository
public interface AppHealthAlertRepository extends MongoRepository<AppHealthAlert, String> {

  /** The shop's open alert, if any (R10.5–R10.6). */
  Optional<AppHealthAlert> findByShopIdAndClearedAtIsNull(String shopId);

  /** Every open alert, for the HR dashboard panel (R10.5). */
  List<AppHealthAlert> findByClearedAtIsNull();
}
