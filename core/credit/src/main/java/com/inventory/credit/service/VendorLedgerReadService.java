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
 * Read access to vendor credit entries for reporting modules.
 *
 * <p>Exists so analytics can read the vendor ledger without importing this module's repository. The
 * {@link CreditPartyType#VENDOR} filter is applied here rather than by callers, so a report cannot
 * accidentally pull customer entries into a vendor ledger.
 */
@Service
@RequiredArgsConstructor
public class VendorLedgerReadService {

  private final CreditEntryRepository creditEntryRepository;

  /**
   * Vendor entries by transaction date, inclusive.
   *
   * @param entryTypes types to include; null or empty means every type
   */
  @Transactional(readOnly = true)
  public List<CreditEntry> findVendorEntriesByTxnDate(
      String shopId, List<CreditEntryType> entryTypes, LocalDate fromInclusive, LocalDate toInclusive) {
    if (entryTypes == null || entryTypes.isEmpty()) {
      return creditEntryRepository.findByShopIdAndPartyTypeAndTxnDateBetween(
          shopId, CreditPartyType.VENDOR, fromInclusive, toInclusive);
    }
    return creditEntryRepository.findByShopIdAndPartyTypeAndEntryTypeInAndTxnDateBetween(
        shopId, CreditPartyType.VENDOR, entryTypes, fromInclusive, toInclusive);
  }

  /**
   * Vendor entries by posting time, inclusive.
   *
   * <p>Complements the transaction-date query: an entry with no {@code txnDate}, or one back-dated
   * outside the window, is only reachable by when it was posted.
   */
  @Transactional(readOnly = true)
  public List<CreditEntry> findVendorEntriesByCreatedAt(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return creditEntryRepository.findByShopIdAndPartyTypeAndCreatedAtBetween(
        shopId, CreditPartyType.VENDOR, fromInclusive, toInclusive);
  }
}
