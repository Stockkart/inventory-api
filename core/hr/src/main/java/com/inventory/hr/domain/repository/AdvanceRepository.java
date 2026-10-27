package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.Advance;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Path ids are read only through {@link #findByShopIdAndId}; lists use MongoTemplate. */
@Repository
public interface AdvanceRepository extends MongoRepository<Advance, String> {

  /** 3 s screen-read limit (HR_QUERY_TOO_SLOW beyond it). */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<Advance> findByShopIdAndId(String shopId, String id);
}
