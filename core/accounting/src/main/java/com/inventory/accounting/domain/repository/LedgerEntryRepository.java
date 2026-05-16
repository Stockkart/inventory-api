package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.model.PartyType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LedgerEntryRepository extends MongoRepository<LedgerEntry, String> {

  Page<LedgerEntry> findByShopIdAndAccountIdOrderByPostedAtAsc(
      String shopId, String accountId, Pageable pageable);

  Page<LedgerEntry> findByShopIdAndAccountIdAndTxnDateBetweenOrderByPostedAtAsc(
      String shopId, String accountId, LocalDate from, LocalDate to, Pageable pageable);

  List<LedgerEntry> findFirst1ByShopIdAndAccountIdOrderByPostedAtDesc(
      String shopId, String accountId);

  List<LedgerEntry> findByShopIdAndJournalEntryId(String shopId, String journalEntryId);

  Page<LedgerEntry> findByShopIdAndPartyTypeAndPartyRefIdOrderByPostedAtAsc(
      String shopId, PartyType partyType, String partyRefId, Pageable pageable);

  Page<LedgerEntry>
      findByShopIdAndPartyTypeAndPartyRefIdAndTxnDateBetweenOrderByPostedAtAsc(
          String shopId,
          PartyType partyType,
          String partyRefId,
          LocalDate from,
          LocalDate to,
          Pageable pageable);

  List<LedgerEntry> findByShopIdAndAccountIdAndTxnDateLessThanEqualOrderByPostedAtDesc(
      String shopId, String accountId, LocalDate asOf, Pageable pageable);
}
