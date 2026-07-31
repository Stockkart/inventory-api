package com.inventory.product.service;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to vendor purchase invoices and returns for reporting modules.
 *
 * <p>Exists so analytics can read purchase history without importing this module's repositories.
 * Reporting queries were previously issued straight against {@code VendorPurchaseInvoiceRepository}
 * from analytics, which put knowledge of this aggregate's persistence in another module.
 *
 * <p>Also exposes {@link #tenderFor(VendorPurchaseInvoice)}: how an invoice splits across cash,
 * online and credit is a property of the invoice, so the rule belongs with the invoice rather than
 * being re-derived by each report.
 */
@Service
@RequiredArgsConstructor
public class VendorPurchaseLedgerReadService {

  private final VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;
  private final VendorPurchaseReturnRepository vendorPurchaseReturnRepository;

  /** Invoices whose invoice date falls in the range, inclusive. */
  @Transactional(readOnly = true)
  public List<VendorPurchaseInvoice> findInvoicesByInvoiceDate(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceDateBetween(
        shopId, fromInclusive, toInclusive);
  }

  /**
   * Invoices captured in the range, inclusive.
   *
   * <p>Needed alongside the invoice-date query because {@code invoiceDate} is optional on older
   * records, which would otherwise be invisible to a date-ranged report.
   */
  @Transactional(readOnly = true)
  public List<VendorPurchaseInvoice> findInvoicesByCreatedAt(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return vendorPurchaseInvoiceRepository.findByShopIdAndCreatedAtBetween(
        shopId, fromInclusive, toInclusive);
  }

  @Transactional(readOnly = true)
  public Optional<VendorPurchaseInvoice> findInvoiceById(String invoiceId) {
    return vendorPurchaseInvoiceRepository.findById(invoiceId);
  }

  @Transactional(readOnly = true)
  public List<VendorPurchaseInvoice> findAllInvoices(String shopId) {
    return vendorPurchaseInvoiceRepository.findByShopId(shopId);
  }

  @Transactional(readOnly = true)
  public List<VendorPurchaseReturn> findReturnsByCreatedAt(
      String shopId, Instant fromInclusive, Instant toInclusive) {
    return vendorPurchaseReturnRepository.findByShopIdAndCreatedAtBetween(
        shopId, fromInclusive, toInclusive);
  }

  /**
   * Resolves an invoice's cash / online / credit legs.
   *
   * <p>Prefers the stored split; falls back to deriving from payment method and paid amount for
   * legacy invoices written before the split columns existed.
   */
  public VendorPurchasePaymentBreakdown.Result tenderFor(VendorPurchaseInvoice invoice) {
    BigDecimal total = invoiceTotal(invoice);

    boolean hasStoredSplit =
        invoice.getCashAmount() != null
            || invoice.getOnlineAmount() != null
            || invoice.getCreditAmount() != null;

    if (hasStoredSplit) {
      return VendorPurchasePaymentBreakdown.resolve(
          total,
          invoice.getPaymentMethod(),
          invoice.getPaidAmount(),
          invoice.getCashAmount(),
          invoice.getOnlineAmount(),
          invoice.getCreditAmount());
    }
    return VendorPurchasePaymentBreakdown.deriveForReport(
        total, invoice.getPaymentMethod(), invoice.getPaidAmount());
  }

  /** Stored total, or the sum of its parts when no total was recorded. */
  private BigDecimal invoiceTotal(VendorPurchaseInvoice invoice) {
    BigDecimal stored = zeroIfNull(invoice.getInvoiceTotal());
    if (stored.signum() > 0) {
      return stored;
    }
    return zeroIfNull(invoice.getLineSubTotal())
        .add(zeroIfNull(invoice.getTaxTotal()))
        .add(zeroIfNull(invoice.getShippingCharge()))
        .add(zeroIfNull(invoice.getOtherCharges()))
        .add(zeroIfNull(invoice.getRoundOff()))
        .subtract(zeroIfNull(invoice.getOverallDiscount()));
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
