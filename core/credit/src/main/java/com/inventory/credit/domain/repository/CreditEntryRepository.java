package com.inventory.credit.domain.repository;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditEntryRepository extends MongoRepository<CreditEntry, String> {

  Optional<CreditEntry> findFirstByShopIdAndSourceKey(String shopId, String sourceKey);

  Page<CreditEntry> findByShopIdAndAccountIdOrderByCreatedAtDesc(
      String shopId, String accountId, Pageable pageable);

  List<CreditEntry> findByShopIdAndPartyTypeAndTxnDateBetween(
      String shopId, CreditPartyType partyType, LocalDate from, LocalDate to);

  List<CreditEntry> findByShopIdAndPartyTypeAndEntryTypeInAndTxnDateBetween(
      String shopId,
      CreditPartyType partyType,
      Collection<CreditEntryType> entryTypes,
      LocalDate from,
      LocalDate to);

  List<CreditEntry> findByShopIdAndPartyTypeAndPartyRefIdAndTxnDateBetween(
      String shopId,
      CreditPartyType partyType,
      String partyRefId,
      LocalDate from,
      LocalDate to);

  List<CreditEntry> findByShopIdAndPartyTypeAndCreatedAtBetween(
      String shopId, CreditPartyType partyType, Instant from, Instant to);
}
