package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.GlAccount;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface GlAccountRepository extends MongoRepository<GlAccount, String> {

  List<GlAccount> findByShopIdOrderByCodeAsc(String shopId);

  /**
   * One row per (shopId, code). If legacy duplicates exist, returns the oldest by id so posting
   * does not throw {@link org.springframework.dao.IncorrectResultSizeDataAccessException}.
   */
  Optional<GlAccount> findFirstByShopIdAndCodeOrderByIdAsc(String shopId, String code);

  boolean existsByShopIdAndCode(String shopId, String code);

  long countByShopId(String shopId);
}
