package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to customer credit entries for reporting modules.
 *
 * <p>Customer-side counterpart of {@link VendorLedgerReadService}. Exists so analytics can read the
 * customer ledger without importing this module's repository. The {@link CreditPartyType#CUSTOMER}
 * filter is applied here rather than by callers, so a report cannot accidentally pull vendor
 * entries into a receivable ledger.
 */
@Service
@RequiredArgsConstructor
public class CustomerLedgerReadService {

  private final CreditEntryRepository creditEntryRepository;

  /**
   * Customer entries by transaction date, inclusive.
   *
   * @param entryTypes types to include; null or empty means every type
   */
  @Transactional(readOnly = true)
  public List<CreditEntry> findCustomerEntriesByTxnDate(
      String shopId,
      List<CreditEntryType> entryTypes,
      LocalDate fromInclusive,
      LocalDate toInclusive) {
    if (entryTypes == null || entryTypes.isEmpty()) {
      return creditEntryRepository.findByShopIdAndPartyTypeAndTxnDateBetween(
          shopId, CreditPartyType.CUSTOMER, fromInclusive, toInclusive);
    }
    return creditEntryRepository.findByShopIdAndPartyTypeAndEntryTypeInAndTxnDateBetween(
        shopId, CreditPartyType.CUSTOMER, entryTypes, fromInclusive, toInclusive);
  }

  /**
   * Customer entries by posting time, inclusive.
   *
   * <p>Complements the transaction-date query: an entry with no {@code txnDate}, or one back-dated
   * outside the window, is only reachable by when it was posted.
   */
  @Transactional(readOnly = true)
  public List<CreditEntry> findCustomerEntriesByCreatedAt(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return creditEntryRepository.findByShopIdAndPartyTypeAndCreatedAtBetween(
        shopId, CreditPartyType.CUSTOMER, fromInclusive, toInclusive);
  }
}
