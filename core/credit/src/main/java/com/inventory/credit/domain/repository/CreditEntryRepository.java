package com.inventory.credit.domain.repository;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditPartyType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditEntryRepository extends MongoRepository<CreditEntry, String> {

  Optional<CreditEntry> findFirstByShopIdAndSourceKey(String shopId, String sourceKey);

  Page<CreditEntry> findByShopIdAndAccountIdOrderByCreatedAtDesc(
      String shopId, String accountId, Pageable pageable);

  List<CreditEntry> findByShopIdAndPartyType(String shopId, CreditPartyType partyType);

  List<CreditEntry> findByShopIdAndPartyTypeAndPartyRefId(
      String shopId, CreditPartyType partyType, String partyRefId);
}
