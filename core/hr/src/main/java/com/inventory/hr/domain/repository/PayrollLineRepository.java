package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.PayrollLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Every method takes {@code shopId}; bulk writes go through MongoTemplate. */
@Repository
public interface PayrollLineRepository extends MongoRepository<PayrollLine, String> {

  /** 3 s screen-read limit (HR_QUERY_TOO_SLOW beyond it). */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<PayrollLine> findByShopIdAndId(String shopId, String id);

  /** All lines of a run (≤ 1,000, the active-employee cap) in one reply; month-wide read limit. */
  @Meta(maxExecutionTimeMs = 10_000, cursorBatchSize = 50_000)
  List<PayrollLine> findByShopIdAndRunId(String shopId, String runId);
}
