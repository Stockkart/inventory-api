package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.LeaveType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LeaveTypeRepository extends MongoRepository<LeaveType, String> {

  /** 3 s screen-read limit (HR_QUERY_TOO_SLOW beyond it); used on every leave write. */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<LeaveType> findByShopIdAndId(String shopId, String id);

  List<LeaveType> findByShopIdOrderByCreatedAtAsc(String shopId);

  boolean existsByShopIdAndNameIgnoreCase(String shopId, String name);
}
