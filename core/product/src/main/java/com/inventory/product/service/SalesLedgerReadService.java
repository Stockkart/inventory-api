package com.inventory.product.service;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to completed sales and sales returns for reporting modules.
 *
 * <p>Customer-side counterpart of {@link VendorPurchaseLedgerReadService}. Exists so analytics can
 * read sales history without importing this module's repositories — reporting queries were
 * previously issued straight against {@code PurchaseRepository} and {@code RefundRepository} from
 * analytics, which put knowledge of this aggregate's persistence in another module.
 *
 * <p>Also exposes {@link #tenderFor(Purchase)}: how a sale splits across cash, online and credit is
 * a property of the sale, so the rule belongs with the sale rather than being re-derived by each
 * report.
 */
@Service
@RequiredArgsConstructor
public class SalesLedgerReadService {

  private final PurchaseRepository purchaseRepository;
  private final RefundRepository refundRepository;

  /**
   * Completed sales sold in the range, inclusive.
   *
   * <p>Only {@link PurchaseStatus#COMPLETED} rows are money movements; the filter is applied here
   * rather than by callers so a report cannot accidentally count a held or cancelled cart.
   */
  @Transactional(readOnly = true)
  public List<Purchase> findCompletedSalesBySoldAt(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return purchaseRepository.findByShopIdAndStatusAndSoldAtBetween(
        shopId, PurchaseStatus.COMPLETED, fromInclusive, toInclusive);
  }

  @Transactional(readOnly = true)
  public Optional<Purchase> findSaleById(String saleId) {
    return purchaseRepository.findById(saleId);
  }

  /** Sales returns raised in the range, inclusive. */
  @Transactional(readOnly = true)
  public List<Refund> findRefundsByCreatedAt(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return refundRepository.findByShopIdAndCreatedAtBetween(shopId, fromInclusive, toInclusive);
  }

  /**
   * Resolves a sale's cash / online / credit legs.
   *
   * <p>Prefers the stored split; falls back to deriving from the payment method for legacy sales
   * written before the split columns existed. The split arithmetic is shared with vendor purchases
   * — the two documents record tender the same way, so the rule lives in one place.
   */
  public VendorPurchasePaymentBreakdown.Result tenderFor(Purchase sale) {
    BigDecimal total = saleTotal(sale);

    boolean hasStoredSplit =
        sale.getCashAmount() != null
            || sale.getOnlineAmount() != null
            || sale.getCreditAmount() != null;

    if (hasStoredSplit) {
      return VendorPurchasePaymentBreakdown.resolve(
          total,
          sale.getPaymentMethod(),
          null,
          sale.getCashAmount(),
          sale.getOnlineAmount(),
          sale.getCreditAmount());
    }
    return VendorPurchasePaymentBreakdown.deriveForReport(total, sale.getPaymentMethod(), null);
  }

  /** Stored grand total, or the sum of its parts when no total was recorded. */
  private BigDecimal saleTotal(Purchase sale) {
    BigDecimal stored = zeroIfNull(sale.getGrandTotal());
    if (stored.signum() > 0) {
      return stored;
    }
    return zeroIfNull(sale.getSubTotal())
        .add(zeroIfNull(sale.getTaxTotal()))
        .subtract(zeroIfNull(sale.getDiscountTotal()));
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
