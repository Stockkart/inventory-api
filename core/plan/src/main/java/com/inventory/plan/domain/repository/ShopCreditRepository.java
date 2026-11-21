package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ShopCredit;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopCreditRepository extends MongoRepository<ShopCredit, String> {

  /** The wallet without its {@code recentReferences} list, which balance reads never need. */
  @Query(value = "{ '_id': ?0 }", fields = "{ 'recentReferences': 0 }")
  Optional<ShopCredit> findBalancesById(String shopId);
}
