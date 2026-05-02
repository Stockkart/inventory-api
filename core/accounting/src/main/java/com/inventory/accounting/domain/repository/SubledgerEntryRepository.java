package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.model.SubledgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SubledgerEntryRepository extends MongoRepository<SubledgerEntry, String> {

  List<SubledgerEntry> findByShopIdAndPartyTypeAndPartyIdOrderByPostedAtAsc(
      String shopId, PartyType partyType, String partyId);

  Page<SubledgerEntry> findByShopIdOrderByPostedAtDesc(String shopId, Pageable pageable);

  Page<SubledgerEntry> findByShopIdAndPartyTypeAndPartyIdOrderByPostedAtDesc(
      String shopId, PartyType partyType, String partyId, Pageable pageable);
}
