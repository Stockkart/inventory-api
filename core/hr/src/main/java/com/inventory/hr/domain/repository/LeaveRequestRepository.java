package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.LeaveRequest;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Path ids are read only through {@link #findByShopIdAndId}; lists and overlays use MongoTemplate. */
@Repository
public interface LeaveRequestRepository extends MongoRepository<LeaveRequest, String> {

  /** 3 s screen-read limit (HR_QUERY_TOO_SLOW beyond it). */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<LeaveRequest> findByShopIdAndId(String shopId, String id);
}
