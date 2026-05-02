package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.JournalEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface JournalEntryRepository extends MongoRepository<JournalEntry, String> {

  Page<JournalEntry> findByShopIdOrderByPostedAtDesc(String shopId, Pageable pageable);

  Page<JournalEntry> findByShopIdOrderByPostedAtAsc(String shopId, Pageable pageable);

  Optional<JournalEntry> findByShopIdAndSourceKey(String shopId, String sourceKey);

  /** Journals that embed at least one {@code lines[].accountId} matching the argument. */
  @Query("{ 'shopId': ?0, 'lines.accountId': ?1 }")
  List<JournalEntry> findByShopIdAndLineAccountId(String shopId, String accountId);

  long countByShopId(String shopId);
}
