package com.inventory.credit.domain.repository;

import com.inventory.credit.domain.model.CreditEntry;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditEntryRepository extends MongoRepository<CreditEntry, String> {

  Optional<CreditEntry> findFirstByShopIdAndSourceKey(String shopId, String sourceKey);

  Page<CreditEntry> findByShopIdAndAccountIdOrderByCreatedAtDesc(
      String shopId, String accountId, Pageable pageable);
}
