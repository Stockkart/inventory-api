package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.PayrollRun;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Path ids are read only through {@link #findByShopIdAndId}. State changes (claims) go through
 * {@code MongoTemplate.findAndModify}, never a whole-document save.
 */
@Repository
public interface PayrollRunRepository extends MongoRepository<PayrollRun, String> {

  /** 3 s screen-read limit (HR_QUERY_TOO_SLOW beyond it). */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<PayrollRun> findByShopIdAndId(String shopId, String id);

  /** Unique {@code (shopId, period)}. */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<PayrollRun> findByShopIdAndPeriod(String shopId, String period);
}
