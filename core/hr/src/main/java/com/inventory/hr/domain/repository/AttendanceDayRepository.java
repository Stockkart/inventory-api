package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.AttendanceDay;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Register queries come with task 5.3; only the setup-status check is needed so far. */
@Repository
public interface AttendanceDayRepository extends MongoRepository<AttendanceDay, String> {

  boolean existsByShopId(String shopId);
}
