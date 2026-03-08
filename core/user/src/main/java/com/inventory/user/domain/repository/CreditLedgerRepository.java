package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.CreditLedger;
import com.inventory.user.domain.model.LedgerPartyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditLedgerRepository extends MongoRepository<CreditLedger, String> {

  List<CreditLedger> findByShopIdAndPartyTypeAndPartyId(
      String shopId, LedgerPartyType partyType, String partyId);

  Page<CreditLedger> findByShopIdOrderByCreatedAtDesc(String shopId, Pageable pageable);

  Page<CreditLedger> findByShopIdAndPartyTypeAndPartyIdOrderByCreatedAtDesc(
      String shopId, LedgerPartyType partyType, String partyId, Pageable pageable);

  /**
   * Find entries for a shop filtered by party type (e.g. VENDOR for payables).
   */
  List<CreditLedger> findByShopIdAndPartyType(String shopId, LedgerPartyType partyType);

  /**
   * Find entries where this shop is the counterparty (vendor's shop).
   * Used when vendor logs into their shop to see amounts to collect from buyer shops.
   */
  List<CreditLedger> findByCounterpartyShopId(String counterpartyShopId);

  /**
   * Find entries where this shop is either the buyer (shopId) or the vendor (counterpartyShopId).
   * Used to show full ledger: both amounts we owe and amounts owed to us.
   */
  Page<CreditLedger> findByShopIdOrCounterpartyShopIdOrderByCreatedAtDesc(
      String shopId, String counterpartyShopId, Pageable pageable);
}
