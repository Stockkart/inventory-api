package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

/** Every method takes {@code shopId}; path ids are read only through {@link #findByShopIdAndId}. */
@Repository
public interface EmployeeRepository extends MongoRepository<Employee, String> {

  Optional<Employee> findByShopIdAndId(String shopId, String id);

  /**
   * The employee linked to a login. The {@code $type} condition repeats the partial index filter,
   * so the planner can use {@code shop_user_unique} (a partial index is only eligible when the
   * query implies its filter).
   */
  @Query("{ 'shopId': ?0, 'userId': { $eq: ?1, $type: 'string' } }")
  Optional<Employee> findByShopIdAndUserId(String shopId, String userId);

  boolean existsByShopIdAndCode(String shopId, String code);

  long countByShopIdAndStatus(String shopId, EmployeeStatus status);

  /** How many of the given employees have the given status (R1.9 paired count, one read). */
  long countByShopIdAndIdInAndStatus(String shopId, Collection<String> ids, EmployeeStatus status);

  /** Linked employees of the shop, ids and userIds only (login picker). */
  @Query(value = "{ 'shopId': ?0, 'userId': { $type: 'string' } }", fields = "{ 'userId': 1 }")
  List<Employee> findLinkedByShopId(String shopId);

  /**
   * Every employee, in any shop, linked to this login with the given status. The one read that
   * crosses shops on purpose: a WORKER account may hold one ACTIVE employee anywhere (worker-app
   * R5.1), so a join request or an approval first asks whether one already exists.
   */
  @Query("{ 'userId': { $eq: ?0, $type: 'string' }, 'status': ?1 }")
  List<Employee> findByUserIdAndStatus(String userId, EmployeeStatus status);

  /**
   * The shop's ACTIVE and EXITED employees with only the fields the possible-match rule and the
   * match panel need (worker-app R4.3): one read per inbox page, matched in memory against each
   * request's snapshot so the phone comparison can use the shared normalisation rule.
   */
  @Query(value = "{ 'shopId': ?0, 'status': { $in: ?1 } }",
      fields = "{ 'code': 1, 'name': 1, 'status': 1, 'userId': 1, 'email': 1, 'phone': 1 }")
  List<Employee> findMatchCandidates(String shopId, Collection<EmployeeStatus> statuses);
}
