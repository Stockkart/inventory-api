package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.api.JournalSource;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface JournalEntryRepository extends MongoRepository<JournalEntry, String> {

  Optional<JournalEntry> findByShopIdAndId(String shopId, String id);

  /**
   * The entry of one source. The {@code $type: 'string'} repeats the partial filter of {@code
   * shop_source_unique} (JournalSourceIndexMigration) so the planner may use that index: an
   * equality alone does not imply the filter. Callers never pass a null {@code sourceId}.
   */
  @org.springframework.data.mongodb.repository.Query(
      "{ 'shopId': ?0, 'sourceType': ?1, 'sourceId': { $eq: ?2, $type: 'string' } }")
  Optional<JournalEntry> findByShopIdAndSourceTypeAndSourceId(
      String shopId, JournalSource sourceType, String sourceId);

  Page<JournalEntry> findByShopIdOrderByTxnDateDescPostedAtDesc(String shopId, Pageable pageable);

  Page<JournalEntry> findByShopIdAndTxnDateBetweenOrderByTxnDateDescPostedAtDesc(
      String shopId, LocalDate from, LocalDate to, Pageable pageable);

  Page<JournalEntry> findByShopIdAndSourceTypeOrderByTxnDateDescPostedAtDesc(
      String shopId, JournalSource sourceType, Pageable pageable);

  long countByShopId(String shopId);
}
