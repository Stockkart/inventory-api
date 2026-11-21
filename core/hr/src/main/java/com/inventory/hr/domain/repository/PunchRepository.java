package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.Punch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Punches ({@code hr_punches}); indexes {@code (shopId, employeeId, localDate)} and unique {@code
 * (shopId, dedupeKey)}. Every read here is covered by one of them (HR-module R10.11).
 */
public interface PunchRepository extends MongoRepository<Punch, String> {

  /** One employee-day, oldest first. */
  @Meta(maxExecutionTimeMs = 3000)
  List<Punch> findByShopIdAndEmployeeIdAndLocalDateOrderByAtAsc(String shopId, String employeeId,
      String localDate);

  /** The punch behind a dedupe key (the Attendance app's replay check, R5.10). */
  @Meta(maxExecutionTimeMs = 3000)
  Optional<Punch> findByShopIdAndDedupeKey(String shopId, String dedupeKey);

  /**
   * One employee's punches whose day lies in {@code [from, to]} (both {@code yyyy-MM-dd},
   * inclusive), newest first: the Attendance app's history screen (R4.10).
   */
  @Meta(maxExecutionTimeMs = 3000)
  @Query(value = "{ 'shopId': ?0, 'employeeId': ?1, 'localDate': { $gte: ?2, $lte: ?3 } }",
      sort = "{ 'at': -1 }")
  List<Punch> findRangeNewestFirst(String shopId, String employeeId, String from, String to);
}
