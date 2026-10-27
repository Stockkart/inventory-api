package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.*;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.product.domain.model.Inventory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Profit-tab numbers from grouped sale lines ({@link AnalyticsSalesQueries}) and the lots they sold
 * from (read once). Cost is the lot's current valuation cost, as before; per-line rules unchanged.
 */
@Component
@Slf4j
public class ProfitAnalyticsUtils {

  /** Product-level profit, one row per lot sold. */
  public List<ProductProfitDto> calculateProductProfits(List<LineGroup> groups, Map<String, Inventory> lots) {
    Map<String, ProductProfitData> productMap = new HashMap<>();

    for (LineGroup g : groups) {
      String inventoryId = g.lotId();
      ProductProfitData data = productMap.computeIfAbsent(inventoryId, id -> {
        ProductProfitData d = new ProductProfitData(id);
        Inventory inv = lot(lots, id);
        if (inv != null) {
          d.costPrice = cost(inv);
          d.productName = inv.getName();
          d.lotId = inv.getLotId();
          d.companyName = inv.getCompanyName();
          d.businessType = inv.getBusinessType();
        }
        return d;
      });
      BigDecimal costPrice = data.costPrice != null ? data.costPrice : BigDecimal.ZERO;
      data.add(g, costPrice);
    }

    return productMap.values().stream()
        .map(data -> new ProductProfitDto(
            data.inventoryId,
            data.productName,
            data.lotId,
            data.companyName,
            data.businessType,
            data.totalQuantitySold,
            data.totalRevenue,
            data.totalCost,
            data.grossProfit,
            margin(data.grossProfit, data.totalRevenue),
            data.numberOfSales))
        .sorted((a, b) -> b.getGrossProfit().compareTo(a.getGrossProfit()))
        .collect(Collectors.toList());
  }

  /** Grouped by the lot's product name ("Unknown" when missing or the lot is gone). */
  public List<ProfitByGroupDto> calculateProfitByProduct(List<LineGroup> groups, Map<String, Inventory> lots) {
    return byGroup(groups, lots, inv -> inv == null || inv.getName() == null ? "Unknown" : inv.getName());
  }

  /** Grouped by the lot's lotId ("Unknown" when the lot is gone). */
  public List<ProfitByGroupDto> calculateProfitByLotId(List<LineGroup> groups, Map<String, Inventory> lots) {
    return byGroup(groups, lots, inv -> inv == null ? "Unknown" : inv.getLotId());
  }

  /** Grouped by the lot's business type ("Unknown" when missing or the lot is gone). */
  public List<ProfitByGroupDto> calculateProfitByBusinessType(List<LineGroup> groups, Map<String, Inventory> lots) {
    return byGroup(groups, lots, inv -> inv == null || inv.getBusinessType() == null ? "Unknown" : inv.getBusinessType());
  }

  private List<ProfitByGroupDto> byGroup(
      List<LineGroup> groups, Map<String, Inventory> lots, Function<Inventory, String> keyOf) {
    Map<String, ProductProfitData> map = new HashMap<>();
    for (LineGroup g : groups) {
      Inventory inv = lot(lots, g.lotId());
      String key = keyOf.apply(inv);
      map.computeIfAbsent(key, ProductProfitData::new).add(g, inv == null ? BigDecimal.ZERO : cost(inv));
    }
    return map.values().stream()
        .map(data -> new ProfitByGroupDto(
            data.inventoryId,
            data.totalQuantitySold,
            data.totalRevenue,
            data.totalCost,
            data.grossProfit,
            margin(data.grossProfit, data.totalRevenue),
            data.numberOfSales))
        .sorted((a, b) -> b.getGrossProfit().compareTo(a.getGrossProfit()))
        .collect(Collectors.toList());
  }

  /** Discount impact over the period's lines. */
  public DiscountImpactDto calculateDiscountImpact(List<LineGroup> groups) {
    BigDecimal totalDiscountGiven = BigDecimal.ZERO;
    BigDecimal totalRevenueWithDiscount = BigDecimal.ZERO;
    BigDecimal estimatedRevenueWithoutDiscount = BigDecimal.ZERO;
    int totalItemsWithDiscount = 0;
    int totalItemsSold = 0;

    for (LineGroup g : groups) {
      int quantity = g.baseQuantity();
      BigDecimal pricingQuantity = g.pricingQuantity();
      BigDecimal priceToRetail = g.line().getPriceToRetail() != null ? g.line().getPriceToRetail() : BigDecimal.ZERO;
      BigDecimal discount = g.line().getDiscount() != null ? g.line().getDiscount() : BigDecimal.ZERO;
      BigDecimal mrp = g.line().getMaximumRetailPrice() != null ? g.line().getMaximumRetailPrice() : priceToRetail;

      totalRevenueWithDiscount = totalRevenueWithDiscount.add(g.times(priceToRetail.multiply(pricingQuantity)));
      totalDiscountGiven = totalDiscountGiven.add(g.times(discount.multiply(pricingQuantity)));
      estimatedRevenueWithoutDiscount = estimatedRevenueWithoutDiscount.add(g.times(mrp.multiply(pricingQuantity)));
      totalItemsSold += g.times(quantity);
      if (discount.compareTo(BigDecimal.ZERO) > 0) {
        totalItemsWithDiscount += g.times(quantity);
      }
    }

    BigDecimal revenueLostToDiscount = estimatedRevenueWithoutDiscount.subtract(totalRevenueWithDiscount);
    BigDecimal discountPercentOfRevenue = BigDecimal.ZERO;
    if (totalRevenueWithDiscount.compareTo(BigDecimal.ZERO) > 0) {
      discountPercentOfRevenue = totalDiscountGiven
          .divide(totalRevenueWithDiscount, 4, RoundingMode.HALF_UP)
          .multiply(BigDecimal.valueOf(100));
    }

    BigDecimal averageDiscountPerItem = BigDecimal.ZERO;
    if (totalItemsWithDiscount > 0) {
      averageDiscountPerItem = totalDiscountGiven
          .divide(BigDecimal.valueOf(totalItemsWithDiscount), 2, RoundingMode.HALF_UP);
    }

    return new DiscountImpactDto(
        totalDiscountGiven,
        totalRevenueWithDiscount,
        estimatedRevenueWithoutDiscount,
        revenueLostToDiscount,
        discountPercentOfRevenue,
        totalItemsWithDiscount,
        totalItemsSold,
        averageDiscountPerItem
    );
  }

  /**
   * Cost vs selling price per chart bucket (IST). {@code groups} must be bucketed
   * ({@link AnalyticsSalesQueries#lineGroups} with buckets); {@code billBuckets} are the buckets that
   * have bills, so a bucket whose bills have no lines still shows (with zeros), as before.
   */
  public List<CostPriceTrendDto> calculateCostPriceTrends(
      List<LineGroup> groups,
      Collection<String> billBuckets,
      Map<String, Inventory> lots,
      String granularity) {

    Map<String, CostTrendData> trendMap = new HashMap<>();
    for (String bucket : billBuckets) {
      trendMap.computeIfAbsent(AnalyticsPeriods.keyOf(bucket, granularity), CostTrendData::new);
    }
    for (LineGroup g : groups) {
      CostTrendData data = trendMap.computeIfAbsent(AnalyticsPeriods.keyOf(g.bucket(), granularity), CostTrendData::new);
      Inventory inv = lot(lots, g.lotId());
      BigDecimal costPrice = inv == null ? BigDecimal.ZERO : cost(inv);
      BigDecimal pricingQuantity = g.pricingQuantity();
      BigDecimal priceToRetail = g.line().getPriceToRetail() != null ? g.line().getPriceToRetail() : BigDecimal.ZERO;

      data.totalQuantitySold += g.times(g.baseQuantity());
      data.totalPriceToRetail = data.totalPriceToRetail.add(g.times(priceToRetail.multiply(pricingQuantity)));
      data.totalCostPrice = data.totalCostPrice.add(g.times(costPrice.multiply(pricingQuantity)));
      data.totalPricingQuantity = data.totalPricingQuantity.add(g.times(pricingQuantity));
    }

    return trendMap.values().stream()
        .sorted(Comparator.comparing(CostTrendData::getPeriodKey))
        .map(data -> {
          BigDecimal averageCostPrice = BigDecimal.ZERO;
          BigDecimal averagePriceToRetail = BigDecimal.ZERO;
          if (data.totalPricingQuantity.compareTo(BigDecimal.ZERO) > 0) {
            averageCostPrice = data.totalCostPrice
                .divide(data.totalPricingQuantity, 2, RoundingMode.HALF_UP);
            averagePriceToRetail = data.totalPriceToRetail
                .divide(data.totalPricingQuantity, 2, RoundingMode.HALF_UP);
          }

          BigDecimal averageMargin = averagePriceToRetail.subtract(averageCostPrice);
          BigDecimal averageMarginPercent = BigDecimal.ZERO;
          if (averagePriceToRetail.compareTo(BigDecimal.ZERO) > 0) {
            averageMarginPercent = averageMargin
                .divide(averagePriceToRetail, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          Instant periodStart = AnalyticsPeriods.start(data.periodKey, granularity);
          Instant periodEnd = AnalyticsPeriods.end(periodStart, granularity);

          return new CostPriceTrendDto(
              data.periodKey,
              periodStart,
              periodEnd,
              averageCostPrice,
              averagePriceToRetail,
              averageMargin,
              averageMarginPercent,
              data.totalQuantitySold
          );
        })
        .collect(Collectors.toList());
  }

  /**
   * Get products with low margins (below threshold).
   */
  public List<ProductProfitDto> getLowMarginProducts(List<ProductProfitDto> productProfits, BigDecimal marginThreshold) {
    if (marginThreshold == null || marginThreshold.compareTo(BigDecimal.ZERO) <= 0) {
      marginThreshold = BigDecimal.valueOf(10); // Default 10%
    }

    final BigDecimal threshold = marginThreshold;
    return productProfits.stream()
        .filter(p -> p.getMarginPercent() != null && p.getMarginPercent().compareTo(threshold) < 0)
        .sorted((a, b) -> a.getMarginPercent().compareTo(b.getMarginPercent())) // Sort by margin (lowest first)
        .collect(Collectors.toList());
  }

  private static Inventory lot(Map<String, Inventory> lots, String id) {
    return id == null ? null : lots.get(id);
  }

  private static BigDecimal cost(Inventory inv) {
    return inv.costForValuation() != null ? inv.costForValuation() : BigDecimal.ZERO;
  }

  private static BigDecimal margin(BigDecimal grossProfit, BigDecimal totalRevenue) {
    if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
      return grossProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }
    return BigDecimal.ZERO;
  }

  /** Running sums for one row (a lot, or a group key held in {@code inventoryId}). */
  private static class ProductProfitData {
    String inventoryId;
    String productName;
    String lotId;
    String companyName;
    String businessType;
    BigDecimal costPrice;
    int totalQuantitySold = 0;
    BigDecimal totalRevenue = BigDecimal.ZERO;
    BigDecimal totalCost = BigDecimal.ZERO;
    BigDecimal grossProfit = BigDecimal.ZERO;
    int numberOfSales = 0;

    ProductProfitData(String inventoryId) {
      this.inventoryId = inventoryId;
    }

    /** One group of identical lines at {@code costPrice}: the old per-line sums, times the count. */
    void add(LineGroup g, BigDecimal costPrice) {
      BigDecimal pricingQuantity = g.pricingQuantity();
      BigDecimal priceToRetail = g.line().getPriceToRetail() != null ? g.line().getPriceToRetail() : BigDecimal.ZERO;
      BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
      BigDecimal itemCost = costPrice.multiply(pricingQuantity);
      totalQuantitySold += g.times(g.baseQuantity());
      totalRevenue = totalRevenue.add(g.times(itemRevenue));
      totalCost = totalCost.add(g.times(itemCost));
      grossProfit = grossProfit.add(g.times(itemRevenue.subtract(itemCost)));
      numberOfSales += g.times(1);
    }
  }

  private static class CostTrendData {
    String periodKey;
    int totalQuantitySold = 0;
    BigDecimal totalPriceToRetail = BigDecimal.ZERO;
    BigDecimal totalCostPrice = BigDecimal.ZERO;
    BigDecimal totalPricingQuantity = BigDecimal.ZERO;

    CostTrendData(String periodKey) {
      this.periodKey = periodKey;
    }

    String getPeriodKey() {
      return periodKey;
    }
  }
}
