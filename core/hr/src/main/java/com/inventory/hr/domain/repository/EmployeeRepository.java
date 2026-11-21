package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
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

  /** Linked employees of the shop, ids and userIds only (login picker). */
  @Query(value = "{ 'shopId': ?0, 'userId': { $type: 'string' } }", fields = "{ 'userId': 1 }")
  List<Employee> findLinkedByShopId(String shopId);
}
