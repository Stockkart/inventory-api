package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.support.BankSummaryEngine;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.StockPeriodSnapshot;
import com.inventory.product.service.MisProductQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes a period's closing stock value so the next period can open from it.
 *
 * <p>Closing a period is what turns the Bank Summary from a recomputed estimate into a
 * ledger: before the close, opening is reconstructed from live counters and moves whenever
 * historical data is edited; after it, opening is a stored number that does not.
 */
@Service
@RequiredArgsConstructor
public class StockPeriodCloseService {

  private final BankSummaryMisService bankSummaryMisService;
  private final MisProductQueryService misProductQueryService;

  /**
   * @param periodEnd last day of the period being closed, inclusive
   * @param force overwrite a close that already exists for this period
   */
  @Transactional
  public StockPeriodSnapshot close(
      String shopId, LocalDate periodEnd, boolean force, String userId) {
    if (periodEnd == null) {
      throw new ValidationException("periodEnd is required to close a stock period.");
    }
    if (periodEnd.isAfter(LocalDate.now())) {
      throw new ValidationException("Cannot close a period that has not ended yet.");
    }

    Optional<StockPeriodSnapshot> existing = misProductQueryService.findSnapshot(shopId, periodEnd);
    if (existing.isPresent() && !force) {
      throw new ValidationException(
          "Period ending "
              + periodEnd
              + " is already closed. Re-close with force=true to overwrite it.");
    }

    // Close the month that ends on this date, so the frozen closing is the one the report
    // for that same month prints.
    LocalDate from = periodEnd.withDayOfMonth(1);
    BankSummaryMisService.Built built = bankSummaryMisService.buildForClose(shopId, from, periodEnd);

    Map<String, BigDecimal> closingByCompany =
        BankSummaryEngine.closingByCompany(built.result().rows());

    StockPeriodSnapshot snapshot =
        existing.orElseGet(
            () -> StockPeriodSnapshot.builder().shopId(shopId).periodEnd(periodEnd).build());
    snapshot.setClosingByCompany(closingByCompany);
    snapshot.setTotalClosing(built.result().totals().getClosing());
    snapshot.setCreatedAt(Instant.now());
    snapshot.setCreatedByUserId(userId);

    return misProductQueryService.saveSnapshot(snapshot);
  }
}
