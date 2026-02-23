package com.inventory.analytics.helper;

import com.inventory.analytics.rest.dto.profit.*;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.repository.InventoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ProfitAnalyticsHelper {

  @Autowired
  private InventoryRepository inventoryRepository;

  /**
   * Calculate product-level profit metrics.
   */
  public List<ProductProfitDto> calculateProductProfits(List<Purchase> purchases) {
    Map<String, ProductProfitData> productMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String inventoryId = item.getInventoryId();
          ProductProfitData data = productMap.getOrDefault(inventoryId, new ProductProfitData(inventoryId));

          // Get inventory details (cost price, lotId, businessType, etc.)
          if (data.costPrice == null) {
            inventoryRepository.findById(inventoryId).ifPresent(inv -> {
              data.costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
              data.productName = inv.getName();
              data.lotId = inv.getLotId();
              data.companyName = inv.getCompanyName();
              data.businessType = inv.getBusinessType();
            });
          }

          // Calculate metrics
          Integer quantity = getBaseQuantity(item);
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal priceToRetail = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
          BigDecimal costPrice = data.costPrice != null ? data.costPrice : BigDecimal.ZERO;

          BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
          BigDecimal itemCost = costPrice.multiply(pricingQuantity);
          BigDecimal itemProfit = itemRevenue.subtract(itemCost);

          data.totalQuantitySold += quantity;
          data.totalRevenue = data.totalRevenue.add(itemRevenue);
          data.totalCost = data.totalCost.add(itemCost);
          data.grossProfit = data.grossProfit.add(itemProfit);
          data.numberOfSales++;

          productMap.put(inventoryId, data);
        }
      }
    }

    return productMap.values().stream()
        .map(data -> {
          BigDecimal marginPercent = BigDecimal.ZERO;
          if (data.totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = data.grossProfit
                .divide(data.totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new ProductProfitDto(
              data.inventoryId,
              data.productName,
              data.lotId,
              data.companyName,
              data.businessType,
              data.totalQuantitySold,
              data.totalRevenue,
              data.totalCost,
              data.grossProfit,
              marginPercent,
              data.numberOfSales
          );
        })
        .sorted((a, b) -> b.getGrossProfit().compareTo(a.getGrossProfit()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate profit grouped by product name.
   */
  public List<ProfitByGroupDto> calculateProfitByProduct(List<Purchase> purchases) {
    Map<String, ProfitGroupData> productMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    // Collect all inventory IDs
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    // Get cost prices from inventory
    Map<String, BigDecimal> inventoryToCostPrice = new HashMap<>();
    Map<String, String> inventoryToProductName = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId).ifPresent(inv -> {
        inventoryToCostPrice.put(inventoryId, inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO);
        inventoryToProductName.put(inventoryId, inv.getName() != null ? inv.getName() : "Unknown");
      });
    }

    // Aggregate by product name
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String productName = inventoryToProductName.getOrDefault(item.getInventoryId(), "Unknown");
          ProfitGroupData data = productMap.getOrDefault(productName, new ProfitGroupData(productName));

          Integer quantity = getBaseQuantity(item);
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal priceToRetail = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
          BigDecimal costPrice = inventoryToCostPrice.getOrDefault(item.getInventoryId(), BigDecimal.ZERO);

          BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
          BigDecimal itemCost = costPrice.multiply(pricingQuantity);
          BigDecimal itemProfit = itemRevenue.subtract(itemCost);

          data.totalQuantitySold += quantity;
          data.totalRevenue = data.totalRevenue.add(itemRevenue);
          data.totalCost = data.totalCost.add(itemCost);
          data.grossProfit = data.grossProfit.add(itemProfit);
          data.numberOfSales++;

          productMap.put(productName, data);
        }
      }
    }

    return productMap.values().stream()
        .map(data -> {
          BigDecimal marginPercent = BigDecimal.ZERO;
          if (data.totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = data.grossProfit
                .divide(data.totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new ProfitByGroupDto(
              data.groupKey,
              data.totalQuantitySold,
              data.totalRevenue,
              data.totalCost,
              data.grossProfit,
              marginPercent,
              data.numberOfSales
          );
        })
        .sorted((a, b) -> b.getGrossProfit().compareTo(a.getGrossProfit()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate profit grouped by lotId.
   */
  public List<ProfitByGroupDto> calculateProfitByLotId(List<Purchase> purchases) {
    Map<String, ProfitGroupData> lotMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    // Collect all inventory IDs
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    // Get lotIds and cost prices from inventory
    Map<String, String> inventoryToLotId = new HashMap<>();
    Map<String, BigDecimal> inventoryToCostPrice = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId).ifPresent(inv -> {
        inventoryToLotId.put(inventoryId, inv.getLotId());
        inventoryToCostPrice.put(inventoryId, inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO);
      });
    }

    // Aggregate by lotId
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String lotId = inventoryToLotId.getOrDefault(item.getInventoryId(), "Unknown");
          ProfitGroupData data = lotMap.getOrDefault(lotId, new ProfitGroupData(lotId));

          Integer quantity = getBaseQuantity(item);
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal priceToRetail = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
          BigDecimal costPrice = inventoryToCostPrice.getOrDefault(item.getInventoryId(), BigDecimal.ZERO);

          BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
          BigDecimal itemCost = costPrice.multiply(pricingQuantity);
          BigDecimal itemProfit = itemRevenue.subtract(itemCost);

          data.totalQuantitySold += quantity;
          data.totalRevenue = data.totalRevenue.add(itemRevenue);
          data.totalCost = data.totalCost.add(itemCost);
          data.grossProfit = data.grossProfit.add(itemProfit);
          data.numberOfSales++;

          lotMap.put(lotId, data);
        }
      }
    }

    return lotMap.values().stream()
        .map(data -> {
          BigDecimal marginPercent = BigDecimal.ZERO;
          if (data.totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = data.grossProfit
                .divide(data.totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new ProfitByGroupDto(
              data.groupKey,
              data.totalQuantitySold,
              data.totalRevenue,
              data.totalCost,
              data.grossProfit,
              marginPercent,
              data.numberOfSales
          );
        })
        .sorted((a, b) -> b.getGrossProfit().compareTo(a.getGrossProfit()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate profit grouped by businessType.
   */
  public List<ProfitByGroupDto> calculateProfitByBusinessType(List<Purchase> purchases) {
    Map<String, ProfitGroupData> businessTypeMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    // Collect all inventory IDs
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    // Get businessTypes and cost prices from inventory
    Map<String, String> inventoryToBusinessType = new HashMap<>();
    Map<String, BigDecimal> inventoryToCostPrice = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId).ifPresent(inv -> {
        inventoryToBusinessType.put(inventoryId, inv.getBusinessType() != null ? inv.getBusinessType() : "Unknown");
        inventoryToCostPrice.put(inventoryId, inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO);
      });
    }

    // Aggregate by businessType
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String businessType = inventoryToBusinessType.getOrDefault(item.getInventoryId(), "Unknown");
          ProfitGroupData data = businessTypeMap.getOrDefault(businessType, new ProfitGroupData(businessType));

          Integer quantity = getBaseQuantity(item);
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal priceToRetail = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
          BigDecimal costPrice = inventoryToCostPrice.getOrDefault(item.getInventoryId(), BigDecimal.ZERO);

          BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
          BigDecimal itemCost = costPrice.multiply(pricingQuantity);
          BigDecimal itemProfit = itemRevenue.subtract(itemCost);

          data.totalQuantitySold += quantity;
          data.totalRevenue = data.totalRevenue.add(itemRevenue);
          data.totalCost = data.totalCost.add(itemCost);
          data.grossProfit = data.grossProfit.add(itemProfit);
          data.numberOfSales++;

          businessTypeMap.put(businessType, data);
        }
      }
    }

    return businessTypeMap.values().stream()
        .map(data -> {
          BigDecimal marginPercent = BigDecimal.ZERO;
          if (data.totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = data.grossProfit
                .divide(data.totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new ProfitByGroupDto(
              data.groupKey,
              data.totalQuantitySold,
              data.totalRevenue,
              data.totalCost,
              data.grossProfit,
              marginPercent,
              data.numberOfSales
          );
        })
        .sorted((a, b) -> b.getGrossProfit().compareTo(a.getGrossProfit()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate discount impact analysis.
   */
  public DiscountImpactDto calculateDiscountImpact(List<Purchase> purchases) {
    BigDecimal totalDiscountGiven = BigDecimal.ZERO;
    BigDecimal totalRevenueWithDiscount = BigDecimal.ZERO;
    BigDecimal estimatedRevenueWithoutDiscount = BigDecimal.ZERO;
    int totalItemsWithDiscount = 0;
    int totalItemsSold = 0;

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          Integer quantity = getBaseQuantity(item);
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal priceToRetail = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
          BigDecimal discount = item.getDiscount() != null ? item.getDiscount() : BigDecimal.ZERO;
          BigDecimal mrp = item.getMaximumRetailPrice() != null ? item.getMaximumRetailPrice() : priceToRetail;

          BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
          BigDecimal itemDiscount = discount.multiply(pricingQuantity);
          BigDecimal itemRevenueWithoutDiscount = mrp.multiply(pricingQuantity);

          totalRevenueWithDiscount = totalRevenueWithDiscount.add(itemRevenue);
          totalDiscountGiven = totalDiscountGiven.add(itemDiscount);
          estimatedRevenueWithoutDiscount = estimatedRevenueWithoutDiscount.add(itemRevenueWithoutDiscount);
          totalItemsSold += quantity;

          if (discount.compareTo(BigDecimal.ZERO) > 0) {
            totalItemsWithDiscount += quantity;
          }
        }
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
   * Calculate cost vs selling price trends (time-series).
   */
  public List<CostPriceTrendDto> calculateCostPriceTrends(
      List<Purchase> purchases,
      Instant startDate,
      Instant endDate,
      String granularity) {

    Map<String, CostTrendData> trendMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    // Collect all inventory IDs
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    // Get cost prices from inventory
    Map<String, BigDecimal> inventoryToCostPrice = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId).ifPresent(inv -> {
        inventoryToCostPrice.put(inventoryId, inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO);
      });
    }

    // Aggregate by time period
    for (Purchase purchase : purchases) {
      if (purchase.getSoldAt() == null) continue;

      String periodKey = getPeriodKey(purchase.getSoldAt(), granularity);
      CostTrendData data = trendMap.getOrDefault(periodKey, new CostTrendData(periodKey));

      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          Integer quantity = getBaseQuantity(item);
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal priceToRetail = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
          BigDecimal costPrice = inventoryToCostPrice.getOrDefault(item.getInventoryId(), BigDecimal.ZERO);

          BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
          BigDecimal itemCost = costPrice.multiply(pricingQuantity);
          BigDecimal itemProfit = itemRevenue.subtract(itemCost);

          data.totalQuantitySold += quantity;
          data.totalRevenue = data.totalRevenue.add(itemRevenue);
          data.totalCost = data.totalCost.add(itemCost);
          data.totalProfit = data.totalProfit.add(itemProfit);
          data.totalPriceToRetail = data.totalPriceToRetail.add(priceToRetail.multiply(pricingQuantity));
          data.totalCostPrice = data.totalCostPrice.add(costPrice.multiply(pricingQuantity));
          data.totalPricingQuantity = data.totalPricingQuantity.add(pricingQuantity);
        }
      }

      trendMap.put(periodKey, data);
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

          Instant periodStart = parsePeriodKey(data.periodKey, granularity);
          Instant periodEnd = getNextPeriod(periodStart, granularity);

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

  // Helper methods for time-series
  private String getPeriodKey(Instant instant, String granularity) {
    LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

    switch (granularity.toLowerCase()) {
      case "hour":
        return dateTime.truncatedTo(ChronoUnit.HOURS).toString();
      case "day":
        return dateTime.toLocalDate().toString();
      case "week":
        LocalDate weekStart = dateTime.toLocalDate().with(java.time.DayOfWeek.MONDAY);
        return weekStart.toString() + "_week";
      case "month":
        return dateTime.getYear() + "-" + String.format("%02d", dateTime.getMonthValue());
      default:
        return dateTime.toLocalDate().toString();
    }
  }

  private Instant parsePeriodKey(String periodKey, String granularity) {
    try {
      switch (granularity.toLowerCase()) {
        case "hour":
          return LocalDateTime.parse(periodKey).atZone(ZoneId.systemDefault()).toInstant();
        case "day":
          return LocalDate.parse(periodKey).atStartOfDay(ZoneId.systemDefault()).toInstant();
        case "week":
          String dateStr = periodKey.replace("_week", "");
          return LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault()).toInstant();
        case "month":
          String[] parts = periodKey.split("-");
          return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1)
              .atStartOfDay(ZoneId.systemDefault()).toInstant();
        default:
          return LocalDate.parse(periodKey).atStartOfDay(ZoneId.systemDefault()).toInstant();
      }
    } catch (Exception e) {
      return Instant.now();
    }
  }

  private Instant getNextPeriod(Instant current, String granularity) {
    switch (granularity.toLowerCase()) {
      case "hour":
        return current.plus(1, ChronoUnit.HOURS);
      case "day":
        return current.plus(1, ChronoUnit.DAYS);
      case "week":
        return current.plus(7, ChronoUnit.DAYS);
      case "month":
        return current.plus(30, ChronoUnit.DAYS);
      default:
        return current.plus(1, ChronoUnit.DAYS);
    }
  }

  // Helper classes
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
  }

  private static class ProfitGroupData {
    String groupKey;
    int totalQuantitySold = 0;
    BigDecimal totalRevenue = BigDecimal.ZERO;
    BigDecimal totalCost = BigDecimal.ZERO;
    BigDecimal grossProfit = BigDecimal.ZERO;
    int numberOfSales = 0;

    ProfitGroupData(String groupKey) {
      this.groupKey = groupKey;
    }
  }

  private static class CostTrendData {
    String periodKey;
    int totalQuantitySold = 0;
    BigDecimal totalRevenue = BigDecimal.ZERO;
    BigDecimal totalCost = BigDecimal.ZERO;
    BigDecimal totalProfit = BigDecimal.ZERO;
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

  private int getBaseQuantity(PurchaseItem item) {
    if (item.getBaseQuantity() != null) {
      return item.getBaseQuantity();
    }
    if (item.getQuantity() == null) {
      return 0;
    }
    int factor = item.getUnitFactor() != null && item.getUnitFactor() > 0 ? item.getUnitFactor() : 1;
    return item.getQuantity().multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP).intValue();
  }

  private BigDecimal getPricingQuantity(PurchaseItem item) {
    if (item.getQuantity() != null) {
      return item.getQuantity();
    }
    if (item.getBaseQuantity() != null) {
      int factor = item.getUnitFactor() != null && item.getUnitFactor() > 0 ? item.getUnitFactor() : 1;
      return BigDecimal.valueOf(item.getBaseQuantity())
          .divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
    }
    return BigDecimal.ZERO;
  }
}

