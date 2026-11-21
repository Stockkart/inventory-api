package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.*;
import com.inventory.analytics.utils.AnalyticsSalesQueries.BillTotals;
import com.inventory.analytics.utils.AnalyticsSalesQueries.BucketTotal;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.product.domain.model.Inventory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sales-tab numbers from grouped sale lines ({@link AnalyticsSalesQueries}) and the lots they sold
 * from (read once). Per-line rules are unchanged; each group counts as {@code lines} identical lines.
 */
@Component
@Slf4j
public class SalesAnalyticsUtils {

  public RevenueSummaryDto calculateRevenueSummary(BillTotals totals) {
    int purchaseCount = Math.toIntExact(totals.bills());
    BigDecimal totalRevenue = totals.grandTotal();
    BigDecimal aov = purchaseCount > 0
        ? totalRevenue.divide(BigDecimal.valueOf(purchaseCount), 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
    return new RevenueSummaryDto(totalRevenue, purchaseCount, aov, totals.taxTotal(), totals.discountTotal());
  }

  /**
   * Per lot sold. The name is the line name on the lot's latest bill (the old code took whichever
   * line it met last, which depended on the database's return order).
   */
  public List<TopProductDto> getTopProducts(List<LineGroup> groups, Map<String, Inventory> lots, int limit) {
    Map<String, TopProductDto> productMap = new HashMap<>();
    Map<String, Instant> nameSeenAt = new HashMap<>();

    for (LineGroup g : groups) {
      String inventoryId = g.lotId();
      TopProductDto dto = productMap.computeIfAbsent(inventoryId, id -> {
        TopProductDto d = new TopProductDto(id, null, null, null, 0, BigDecimal.ZERO, 0);
        Inventory inv = id == null ? null : lots.get(id);
        if (inv != null) {
          d.setLotId(inv.getLotId());
          d.setCompanyName(inv.getCompanyName());
        }
        return d;
      });
      dto.setTotalQuantitySold(dto.getTotalQuantitySold() + g.times(g.baseQuantity()));
      dto.setTotalRevenue(dto.getTotalRevenue().add(g.times(lineRevenue(g))));
      dto.setNumberOfSales(dto.getNumberOfSales() + g.times(1));
      Instant seen = nameSeenAt.get(inventoryId);
      if (seen == null || (g.lastSoldAt() != null && g.lastSoldAt().isAfter(seen))) {
        dto.setProductName(g.line().getName());
        nameSeenAt.put(inventoryId, g.lastSoldAt() != null ? g.lastSoldAt() : Instant.MIN);
      }
    }

    return productMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .limit(limit)
        .collect(Collectors.toList());
  }

  /** Grouped by the name on the sale line ("Unknown" when blank-null). */
  public List<SalesByGroupDto> getSalesByProduct(List<LineGroup> groups) {
    return byGroup(groups, g -> g.line().getName() != null ? g.line().getName() : "Unknown");
  }

  /** Grouped by the lot's lotId ("Unknown" when the lot is gone). */
  public List<SalesByGroupDto> getSalesByLotId(List<LineGroup> groups, Map<String, Inventory> lots) {
    return byGroup(groups, g -> {
      Inventory inv = g.lotId() == null ? null : lots.get(g.lotId());
      return inv == null ? "Unknown" : inv.getLotId();
    });
  }

  /** Grouped by the lot's company ("Unknown" when missing or the lot is gone). */
  public List<SalesByGroupDto> getSalesByCompany(List<LineGroup> groups, Map<String, Inventory> lots) {
    return byGroup(groups, g -> {
      Inventory inv = g.lotId() == null ? null : lots.get(g.lotId());
      return inv == null || inv.getCompanyName() == null ? "Unknown" : inv.getCompanyName();
    });
  }

  private List<SalesByGroupDto> byGroup(List<LineGroup> groups, java.util.function.Function<LineGroup, String> keyOf) {
    Map<String, SalesByGroupDto> map = new HashMap<>();
    for (LineGroup g : groups) {
      String key = keyOf.apply(g);
      SalesByGroupDto dto = map.computeIfAbsent(key, k -> new SalesByGroupDto(k, 0, BigDecimal.ZERO, 0));
      dto.setTotalQuantitySold(dto.getTotalQuantitySold() + g.times(g.baseQuantity()));
      dto.setTotalRevenue(dto.getTotalRevenue().add(g.times(lineRevenue(g))));
      dto.setNumberOfSales(dto.getNumberOfSales() + g.times(1));
    }
    return map.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .collect(Collectors.toList());
  }

  /** Bills per chart bucket (IST), from MongoDB's per-day (or per-hour) totals. */
  public List<TimeSeriesDataDto> getTimeSeriesData(List<BucketTotal> buckets, String granularity) {
    Map<String, TimeSeriesDataDto> timeSeriesMap = new HashMap<>();
    for (BucketTotal b : buckets) {
      String periodKey = AnalyticsPeriods.keyOf(b.bucket(), granularity);
      TimeSeriesDataDto dto = timeSeriesMap.computeIfAbsent(periodKey,
          k -> new TimeSeriesDataDto(k, null, null, BigDecimal.ZERO, 0, null));
      dto.setRevenue(dto.getRevenue().add(b.grandTotal()));
      dto.setPurchaseCount(dto.getPurchaseCount() + Math.toIntExact(b.bills()));
    }

    return timeSeriesMap.values().stream()
        .sorted(Comparator.comparing(TimeSeriesDataDto::getPeriod))
        .map(dto -> {
          int count = dto.getPurchaseCount();
          BigDecimal rev = dto.getRevenue();
          BigDecimal aov = count > 0
              ? rev.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
          Instant periodStart = AnalyticsPeriods.start(dto.getPeriod(), granularity);
          Instant periodEnd = AnalyticsPeriods.end(periodStart, granularity);
          return new TimeSeriesDataDto(dto.getPeriod(), periodStart, periodEnd, rev, count, aov);
        })
        .collect(Collectors.toList());
  }

  public PeriodComparisonDto calculatePeriodComparison(BillTotals current, BillTotals previous) {
    RevenueSummaryDto currentSummary = calculateRevenueSummary(current);
    RevenueSummaryDto previousSummary = calculateRevenueSummary(previous);

    BigDecimal revenueChange = currentSummary.getTotalRevenue().subtract(previousSummary.getTotalRevenue());
    BigDecimal revenueChangePercent = previousSummary.getTotalRevenue().compareTo(BigDecimal.ZERO) > 0
        ? revenueChange.divide(previousSummary.getTotalRevenue(), 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
        : BigDecimal.ZERO;

    int currPurchases = currentSummary.getTotalPurchases() != null ? currentSummary.getTotalPurchases() : 0;
    int prevPurchases = previousSummary.getTotalPurchases() != null ? previousSummary.getTotalPurchases() : 0;
    BigDecimal purchaseCountChange = BigDecimal.valueOf(currPurchases - prevPurchases);
    BigDecimal purchaseCountChangePercent = prevPurchases > 0
        ? purchaseCountChange.divide(BigDecimal.valueOf(prevPurchases), 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
        : BigDecimal.ZERO;

    BigDecimal aovChange = currentSummary.getAverageOrderValue().subtract(previousSummary.getAverageOrderValue());
    BigDecimal aovChangePercent = previousSummary.getAverageOrderValue().compareTo(BigDecimal.ZERO) > 0
        ? aovChange.divide(previousSummary.getAverageOrderValue(), 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
        : BigDecimal.ZERO;

    return new PeriodComparisonDto(
        currentSummary,
        previousSummary,
        revenueChange,
        revenueChangePercent,
        purchaseCountChange,
        purchaseCountChangePercent,
        aovChange,
        aovChangePercent
    );
  }

  /** Price × pricing quantity of one line; 0 without a price. */
  static BigDecimal lineRevenue(LineGroup g) {
    return g.line().getPriceToRetail() != null
        ? g.line().getPriceToRetail().multiply(g.pricingQuantity())
        : BigDecimal.ZERO;
  }
}
