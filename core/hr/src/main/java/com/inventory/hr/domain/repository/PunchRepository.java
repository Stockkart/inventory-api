package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.Punch;
import java.util.List;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Punches ({@code hr_punches}); index {@code (shopId, employeeId, localDate)}. */
public interface PunchRepository extends MongoRepository<Punch, String> {

  /** One employee-day, oldest first. */
  @Meta(maxExecutionTimeMs = 3000)
  List<Punch> findByShopIdAndEmployeeIdAndLocalDateOrderByAtAsc(String shopId, String employeeId,
      String localDate);
}
