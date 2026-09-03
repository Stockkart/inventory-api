package com.inventory.analytics.mis.support;

import com.inventory.analytics.mis.rest.dto.MisBankSummaryRowDto;
import com.inventory.analytics.mis.rest.dto.MisBankSummaryTotalsDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.InventoryCorrection;
import com.inventory.product.domain.model.InventoryCorrectionLine;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.model.enums.InventoryCorrectionLineStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * All the arithmetic behind the Bank Summary, as pure functions over already-fetched data.
 *
 * <h2>Why it works in value, not quantity</h2>
 *
 * The five things that move stock disagree about units. A vendor invoice line counts in
 * display units and excludes scheme-free goods; a sale line carries {@code baseQuantity};
 * a refund line carries a display quantity; a vendor return carries
 * {@code baseQuantityReturned}; a correction carries before/after base counts. Converting
 * them all into one quantity space needs each line's own unit factor, and a refund line
 * does not store one.
 *
 * <p>So every movement is converted straight into <em>value at the lot's own cost</em> the
 * moment it is read, and all the accumulation happens there. Since a lot's cost is a single
 * constant, rolling value backwards is exactly equivalent to rolling quantity backwards and
 * multiplying at the end -- with no unit conversion to get wrong.
 *
 * <h2>Where opening comes from</h2>
 *
 * If a prior period was closed, opening is that frozen snapshot, full stop. Otherwise it is
 * reconstructed by taking today's live stock value and undoing every movement dated on or
 * after the period start:
 *
 * <pre>
 *   opening = live
 *           - receipts_since(start)     // a receipt added stock, so take it back off
 *           + sales_since(start)        // a sale removed stock, so put it back
 *           - refunds_since(start)      // a sales return added stock back
 *           + vendorReturns_since(start)// a purchase return removed stock
 *           - adjustments_since(start)  // a correction moved it by its own delta
 * </pre>
 *
 * <p>Every source is append-only, so this is deterministic. It is wrong only for a lot that
 * was hard-deleted, which leaves no live counter to roll back from.
 */
public final class BankSummaryEngine {

  /** Bucket for stock whose lot carries no company name. */
  public static final String UNCLASSIFIED = "(No company)";

  private static final int SCALE = 2;

  private BankSummaryEngine() {}

  /**
   * Everything the engine reads, all dated on or after the period start. The in-period
   * slice is taken here rather than fetched separately, since it is a subset.
   */
  public record Movements(
      List<VendorPurchaseInvoice> invoicesFromStart,
      List<Purchase> salesFromStart,
      List<Refund> refundsFromStart,
      List<VendorPurchaseReturn> vendorReturnsFromStart,
      List<InventoryCorrection> corrections) {

    public static Movements empty() {
      return new Movements(List.of(), List.of(), List.of(), List.of(), List.of());
    }
  }

  public record Result(
      List<MisBankSummaryRowDto> rows, MisBankSummaryTotalsDto totals, boolean hasAdjustments) {}

  /**
   * @param lots live inventory for the shop, already hydrated with {@code companyName} and
   *     pricing by the read aspects on {@code inventoryRepository}
   * @param openingSnapshot frozen company to closing value from the prior close, or null to
   *     derive opening by rolling {@code lots} backwards
   * @param periodStart first instant of the period
   * @param periodEndExclusive first instant after the period
   */
  public static Result compute(
      List<Inventory> lots,
      Movements movements,
      Map<String, BigDecimal> openingSnapshot,
      Instant periodStart,
      Instant periodEndExclusive) {

    List<Inventory> safeLots = lots != null ? lots : List.of();
    Movements mv = movements != null ? movements : Movements.empty();

    Map<String, LotCost> lotIndex = indexLots(safeLots);
    Map<String, Instant> receiptInstants = receiptInstants(mv.invoicesFromStart(), safeLots);

    Accumulator acc = new Accumulator();

    // --- Receipts. A lot is created whole by one receipt, so the lot's own received
    // count is the honest inward quantity: unlike the invoice line's count, it includes
    // scheme-free goods, which are stock and carry value like any other unit.
    for (Inventory lot : safeLots) {
      LotCost cost = lotIndex.get(lot.getId());
      if (cost == null) {
        continue;
      }
      Instant receivedAt = receiptInstants.get(lot.getId());
      if (receivedAt == null || receivedAt.isBefore(periodStart)) {
        continue;
      }
      BigDecimal value = cost.valueOfBase(nzInt(lot.getReceivedBaseCount()));
      acc.sinceStart(cost.company()).purchase = add(acc.sinceStart(cost.company()).purchase, value);
      if (receivedAt.isBefore(periodEndExclusive)) {
        acc.inPeriod(cost.company()).purchase = add(acc.inPeriod(cost.company()).purchase, value);
      }
    }

    // --- Sales (stock out at cost).
    for (Purchase sale : mv.salesFromStart()) {
      Instant at = sale.getSoldAt();
      if (at == null || at.isBefore(periodStart) || sale.getItems() == null) {
        continue;
      }
      boolean within = at.isBefore(periodEndExclusive);
      for (PurchaseItem item : sale.getItems()) {
        LotCost cost = lotIndex.get(item.getInventoryId());
        BigDecimal value;
        String company;
        if (cost != null) {
          company = cost.company();
          value = cost.valueOfBase(nzInt(item.getBaseQuantity()));
        } else {
          // The lot is gone. Its sale line still snapshotted the company and the cost of
          // goods, which is the only record left of that stock.
          company = companyOr(item.getCompanyName(), UNCLASSIFIED);
          value = nz(item.getCostTotal());
        }
        acc.sinceStart(company).sale = add(acc.sinceStart(company).sale, value);
        if (within) {
          acc.inPeriod(company).sale = add(acc.inPeriod(company).sale, value);
        }
      }
    }

    // --- Sales returns: stock comes back, so they net off the sale column.
    for (Refund refund : mv.refundsFromStart()) {
      Instant at = refund.getCreatedAt();
      if (at == null || at.isBefore(periodStart) || refund.getRefundedItems() == null) {
        continue;
      }
      boolean within = at.isBefore(periodEndExclusive);
      for (RefundItem item : refund.getRefundedItems()) {
        LotCost cost = lotIndex.get(item.getInventoryId());
        BigDecimal value;
        String company;
        if (cost != null) {
          company = cost.company();
          // A refund line counts in display units, so it pairs with the display cost.
          value = cost.valueOfDisplay(nzInt(item.getQuantity()));
        } else {
          company = UNCLASSIFIED;
          value = nz(item.getCogsAmount());
        }
        acc.sinceStart(company).sale = subtract(acc.sinceStart(company).sale, value);
        if (within) {
          acc.inPeriod(company).sale = subtract(acc.inPeriod(company).sale, value);
        }
      }
    }

    // --- Purchase returns: stock goes back to the vendor, so they net off the purchase column.
    for (VendorPurchaseReturn ret : mv.vendorReturnsFromStart()) {
      Instant at = ret.getCreatedAt();
      if (at == null || at.isBefore(periodStart) || ret.getItems() == null) {
        continue;
      }
      boolean within = at.isBefore(periodEndExclusive);
      for (VendorPurchaseReturnItem item : ret.getItems()) {
        LotCost cost = lotIndex.get(item.getInventoryId());
        String company = cost != null ? cost.company() : UNCLASSIFIED;
        BigDecimal value =
            cost != null
                ? cost.valueOfBase(nzInt(item.getBaseQuantityReturned()))
                : nz(item.getTaxableValue());
        acc.sinceStart(company).purchase = subtract(acc.sinceStart(company).purchase, value);
        if (within) {
          acc.inPeriod(company).purchase = subtract(acc.inPeriod(company).purchase, value);
        }
      }
    }

    // --- Stock corrections. A correction belongs to no other column, and dropping it would
    // break the row identity, so it gets its own.
    for (InventoryCorrection correction : mv.corrections()) {
      if (correction.getLines() == null) {
        continue;
      }
      for (InventoryCorrectionLine line : correction.getLines()) {
        if (line.getStatus() != InventoryCorrectionLineStatus.APPROVED) {
          continue;
        }
        Instant at = line.getProcessedAt();
        if (at == null || at.isBefore(periodStart)) {
          continue;
        }
        LotCost cost = lotIndex.get(line.getInventoryId());
        if (cost == null) {
          continue;
        }
        int delta = nzInt(line.getRequestedCurrentBaseCount()) - nzInt(line.getPreviousCurrentBaseCount());
        BigDecimal value = cost.valueOfBase(delta);
        acc.sinceStart(cost.company()).adjustment =
            add(acc.sinceStart(cost.company()).adjustment, value);
        if (at.isBefore(periodEndExclusive)) {
          acc.inPeriod(cost.company()).adjustment =
              add(acc.inPeriod(cost.company()).adjustment, value);
        }
      }
    }

    // --- Live stock value now, per company. The starting point for the backward roll.
    Map<String, BigDecimal> liveValue = new HashMap<>();
    for (Inventory lot : safeLots) {
      LotCost cost = lotIndex.get(lot.getId());
      if (cost == null) {
        continue;
      }
      liveValue.merge(
          cost.company(), cost.valueOfBase(nzInt(lot.getCurrentBaseCount())), BankSummaryEngine::add);
    }

    // --- Assemble one row per company seen anywhere.
    java.util.Set<String> companies = new java.util.LinkedHashSet<>();
    companies.addAll(liveValue.keySet());
    companies.addAll(acc.inPeriodKeys());
    companies.addAll(acc.sinceStartKeys());
    if (openingSnapshot != null) {
      companies.addAll(openingSnapshot.keySet());
    }

    List<MisBankSummaryRowDto> rows = new ArrayList<>(companies.size());
    for (String company : companies) {
      Bucket period = acc.inPeriod(company);
      Bucket since = acc.sinceStart(company);

      BigDecimal opening;
      if (openingSnapshot != null) {
        opening = nz(openingSnapshot.get(company));
      } else {
        opening =
            nz(liveValue.get(company))
                .subtract(since.purchase)
                .add(since.sale)
                .subtract(since.adjustment);
      }

      BigDecimal closing =
          opening.add(period.purchase).subtract(period.sale).add(period.adjustment);

      rows.add(
          MisBankSummaryRowDto.builder()
              .company(company)
              .opening(scale(opening))
              .purchase(scale(period.purchase))
              .sale(scale(period.sale))
              .adjustment(scale(period.adjustment))
              .closing(scale(closing))
              .build());
    }

    rows.removeIf(BankSummaryEngine::isAllZero);
    rows.sort(Comparator.comparing(MisBankSummaryRowDto::getCompany, String::compareToIgnoreCase));

    boolean hasAdjustments =
        rows.stream().anyMatch(r -> r.getAdjustment() != null && r.getAdjustment().signum() != 0);

    return new Result(rows, totalsOf(rows), hasAdjustments);
  }

  /** Grand total, added from the rows so it can never disagree with them. */
  public static MisBankSummaryTotalsDto totalsOf(List<MisBankSummaryRowDto> rows) {
    BigDecimal opening = zero();
    BigDecimal purchase = zero();
    BigDecimal sale = zero();
    BigDecimal adjustment = zero();
    BigDecimal closing = zero();
    if (rows != null) {
      for (MisBankSummaryRowDto r : rows) {
        opening = add(opening, r.getOpening());
        purchase = add(purchase, r.getPurchase());
        sale = add(sale, r.getSale());
        adjustment = add(adjustment, r.getAdjustment());
        closing = add(closing, r.getClosing());
      }
    }
    return MisBankSummaryTotalsDto.builder()
        .companyCount(rows != null ? rows.size() : 0)
        .opening(opening)
        .purchase(purchase)
        .sale(sale)
        .adjustment(adjustment)
        .closing(closing)
        .build();
  }

  /** Company to closing value, for freezing a period close. */
  public static Map<String, BigDecimal> closingByCompany(List<MisBankSummaryRowDto> rows) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (rows != null) {
      for (MisBankSummaryRowDto r : rows) {
        out.put(r.getCompany(), nz(r.getClosing()));
      }
    }
    return out;
  }

  // --- internals ---

  /** A lot's company and its cost, held once per unit basis so callers never divide. */
  private record LotCost(String company, BigDecimal costPerBase, BigDecimal costPerDisplay) {

    BigDecimal valueOfBase(int baseQty) {
      return costPerBase.multiply(BigDecimal.valueOf(baseQty));
    }

    BigDecimal valueOfDisplay(int displayQty) {
      return costPerDisplay.multiply(BigDecimal.valueOf(displayQty));
    }
  }

  private static Map<String, LotCost> indexLots(List<Inventory> lots) {
    Map<String, LotCost> out = new HashMap<>();
    for (Inventory lot : lots) {
      if (lot.getId() == null) {
        continue;
      }
      BigDecimal costPerDisplay = nz(lot.costForValuation());
      BigDecimal costPerBase =
          costPerDisplay.divide(BigDecimal.valueOf(factorOf(lot)), 6, RoundingMode.HALF_UP);
      out.put(
          lot.getId(),
          new LotCost(companyOr(lot.getCompanyName(), UNCLASSIFIED), costPerBase, costPerDisplay));
    }
    return out;
  }

  private static int factorOf(Inventory lot) {
    UnitConversion conv = lot.getUnitConversions();
    if (conv == null || conv.getFactor() == null || conv.getFactor() < 1) {
      return 1;
    }
    return conv.getFactor();
  }

  /**
   * When each lot's stock arrived. The invoice date is what buckets the purchase column, so
   * the backward roll must use the same date or opening and purchase will disagree about
   * which side of the period boundary a receipt fell on. Lots with no invoice fall back to
   * their own received date.
   */
  private static Map<String, Instant> receiptInstants(
      List<VendorPurchaseInvoice> invoices, List<Inventory> lots) {
    Map<String, Instant> out = new HashMap<>();
    for (Inventory lot : lots) {
      if (lot.getId() == null) {
        continue;
      }
      Instant fallback =
          lot.getReceivedDate() != null ? lot.getReceivedDate() : lot.getPurchaseDate();
      if (fallback != null) {
        out.put(lot.getId(), fallback);
      }
    }
    if (invoices != null) {
      for (VendorPurchaseInvoice invoice : invoices) {
        if (invoice.getInvoiceDate() == null || invoice.getLines() == null) {
          continue;
        }
        for (VendorPurchaseInvoiceLine line : invoice.getLines()) {
          if (StringUtils.hasText(line.getInventoryId())) {
            out.put(line.getInventoryId(), invoice.getInvoiceDate());
          }
        }
      }
    }
    return out;
  }

  /** Per-company running values, kept for the period and for everything since its start. */
  private static final class Accumulator {
    private final Map<String, Bucket> inPeriod = new LinkedHashMap<>();
    private final Map<String, Bucket> sinceStart = new LinkedHashMap<>();

    Bucket inPeriod(String company) {
      return inPeriod.computeIfAbsent(company, c -> new Bucket());
    }

    Bucket sinceStart(String company) {
      return sinceStart.computeIfAbsent(company, c -> new Bucket());
    }

    java.util.Set<String> inPeriodKeys() {
      return inPeriod.keySet();
    }

    java.util.Set<String> sinceStartKeys() {
      return sinceStart.keySet();
    }
  }

  private static final class Bucket {
    private BigDecimal purchase = zero();
    private BigDecimal sale = zero();
    private BigDecimal adjustment = zero();
  }

  private static boolean isAllZero(MisBankSummaryRowDto r) {
    return r.getOpening().signum() == 0
        && r.getPurchase().signum() == 0
        && r.getSale().signum() == 0
        && r.getAdjustment().signum() == 0
        && r.getClosing().signum() == 0;
  }

  private static String companyOr(String company, String fallback) {
    return StringUtils.hasText(company) ? company.trim() : fallback;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static int nzInt(Integer v) {
    return v != null ? v : 0;
  }

  private static BigDecimal add(BigDecimal a, BigDecimal b) {
    return nz(a).add(nz(b));
  }

  private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
    return nz(a).subtract(nz(b));
  }

  private static BigDecimal scale(BigDecimal v) {
    return nz(v).setScale(SCALE, RoundingMode.HALF_UP);
  }
}
