package com.inventory.analytics.helper;

import com.inventory.analytics.rest.dto.internal.GroupSalesData;
import com.inventory.analytics.rest.dto.internal.ProductSalesData;
import com.inventory.analytics.rest.dto.internal.TimeSeriesData;
import com.inventory.analytics.rest.dto.sales.*;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
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
public class SalesAnalyticsHelper {

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  /**
   * Get all completed purchases for a shop within date range.
   * Status and soldAt range are filtered in the DB query.
   */
  public List<Purchase> getCompletedPurchases(String shopId, Instant startDate, Instant endDate) {
    return purchaseRepository.findByShopIdAndStatusAndSoldAtBetween(
        shopId, PurchaseStatus.COMPLETED, startDate, endDate);
  }

  /**
   * Calculate revenue summary from purchases.
   */
  public RevenueSummaryDto calculateRevenueSummary(List<Purchase> purchases) {
    BigDecimal totalRevenue = purchases.stream()
        .map(Purchase::getGrandTotal)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalTax = purchases.stream()
        .map(Purchase::getTaxTotal)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalDiscount = purchases.stream()
        .map(Purchase::getDiscountTotal)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    int purchaseCount = purchases.size();
    BigDecimal aov = purchaseCount > 0
        ? totalRevenue.divide(BigDecimal.valueOf(purchaseCount), 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;

    return new RevenueSummaryDto(totalRevenue, purchaseCount, aov, totalTax, totalDiscount);
  }

  /**
   * Get top products by revenue. Batches inventory lookup (single DB call) instead of N findById.
   */
  public List<TopProductDto> getTopProducts(List<Purchase> purchases, int limit) {
    Set<String> inventoryIds = new HashSet<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }
    Map<String, Inventory> inventoryMap = batchLoadInventory(new ArrayList<>(inventoryIds));

    Map<String, ProductSalesData> productMap = new HashMap<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String inventoryId = item.getInventoryId();
          ProductSalesData data = productMap.getOrDefault(inventoryId, new ProductSalesData(inventoryId));
          data.setQuantitySold(data.getQuantitySold() + (item.getQuantity() != null ? item.getQuantity() : 0));
          BigDecimal itemRevenue = item.getSellingPrice() != null && item.getQuantity() != null
              ? item.getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);
          data.setProductName(item.getName());
          Inventory inv = inventoryMap.get(inventoryId);
          if (inv != null) {
            data.setLotId(inv.getLotId());
            data.setCompanyName(inv.getCompanyName());
          }
          productMap.put(inventoryId, data);
        }
      }
    }

    List<TopProductDto> result = productMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .limit(limit)
        .map(data -> new TopProductDto(
            data.getInventoryId(),
            data.getProductName(),
            data.getLotId(),
            data.getCompanyName(),
            data.getQuantitySold(),
            data.getTotalRevenue(),
            data.getNumberOfSales()
        ))
        .collect(Collectors.toList());
    return result;
  }

  /** Batch load inventory by IDs (single DB call). Returns map id -> Inventory. */
  private Map<String, Inventory> batchLoadInventory(List<String> ids) {
    if (ids == null || ids.isEmpty()) return Collections.emptyMap();
    List<Inventory> list = inventoryRepository.findByIdIn(ids);
    return list.stream().collect(Collectors.toMap(Inventory::getId, inv -> inv, (a, b) -> a));
  }

  /**
   * Get sales grouped by product name.
   */
  public List<SalesByGroupDto> getSalesByProduct(List<Purchase> purchases) {
    Map<String, GroupSalesData> productMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String productName = item.getName() != null ? item.getName() : "Unknown";
          GroupSalesData data = productMap.getOrDefault(productName, new GroupSalesData(productName));
          
          data.setQuantitySold(data.getQuantitySold() + (item.getQuantity() != null ? item.getQuantity() : 0));
          BigDecimal itemRevenue = item.getSellingPrice() != null && item.getQuantity() != null
              ? item.getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);

          productMap.put(productName, data);
        }
      }
    }

    return productMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .map(data -> new SalesByGroupDto(data.getGroupKey(), data.getQuantitySold(), data.getTotalRevenue(), data.getNumberOfSales()))
        .collect(Collectors.toList());
  }

  /**
   * Get sales grouped by lotId. Uses single batch inventory lookup instead of N findById.
   */
  public List<SalesByGroupDto> getSalesByLotId(List<Purchase> purchases) {
    Set<String> inventoryIds = new HashSet<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }
    Map<String, Inventory> inventoryMap = batchLoadInventory(new ArrayList<>(inventoryIds));
    Map<String, String> inventoryToLotId = new HashMap<>();
    inventoryMap.forEach((id, inv) -> inventoryToLotId.put(id, inv.getLotId() != null ? inv.getLotId() : "Unknown"));

    Map<String, GroupSalesData> lotMap = new HashMap<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String lotId = inventoryToLotId.getOrDefault(item.getInventoryId(), "Unknown");
          GroupSalesData data = lotMap.getOrDefault(lotId, new GroupSalesData(lotId));
          data.setQuantitySold(data.getQuantitySold() + (item.getQuantity() != null ? item.getQuantity() : 0));
          BigDecimal itemRevenue = item.getSellingPrice() != null && item.getQuantity() != null
              ? item.getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);
          lotMap.put(lotId, data);
        }
      }
    }

    List<SalesByGroupDto> result = lotMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .map(data -> new SalesByGroupDto(data.getGroupKey(), data.getQuantitySold(), data.getTotalRevenue(), data.getNumberOfSales()))
        .collect(Collectors.toList());
    return result;
  }

  /**
   * Get sales grouped by company name. Uses single batch inventory lookup instead of N findById.
   */
  public List<SalesByGroupDto> getSalesByCompany(List<Purchase> purchases) {
    Set<String> inventoryIds = new HashSet<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }
    Map<String, Inventory> inventoryMap = batchLoadInventory(new ArrayList<>(inventoryIds));
    Map<String, String> inventoryToCompany = new HashMap<>();
    inventoryMap.forEach((id, inv) -> inventoryToCompany.put(id, inv.getCompanyName() != null ? inv.getCompanyName() : "Unknown"));

    Map<String, GroupSalesData> companyMap = new HashMap<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String companyName = inventoryToCompany.getOrDefault(item.getInventoryId(), "Unknown");
          GroupSalesData data = companyMap.getOrDefault(companyName, new GroupSalesData(companyName));
          data.setQuantitySold(data.getQuantitySold() + (item.getQuantity() != null ? item.getQuantity() : 0));
          BigDecimal itemRevenue = item.getSellingPrice() != null && item.getQuantity() != null
              ? item.getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);
          companyMap.put(companyName, data);
        }
      }
    }

    List<SalesByGroupDto> result = companyMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .map(data -> new SalesByGroupDto(data.getGroupKey(), data.getQuantitySold(), data.getTotalRevenue(), data.getNumberOfSales()))
        .collect(Collectors.toList());
    return result;
  }

  /**
   * Get time-series data for purchases.
   */
  public List<TimeSeriesDataDto> getTimeSeriesData(
      List<Purchase> purchases,
      Instant startDate,
      Instant endDate,
      String granularity) {

    Map<String, TimeSeriesData> timeSeriesMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getSoldAt() == null) continue;

      String periodKey = getPeriodKey(purchase.getSoldAt(), granularity);
      TimeSeriesData data = timeSeriesMap.getOrDefault(periodKey, new TimeSeriesData(periodKey));
      
      data.setRevenue(data.getRevenue().add(purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO));
      data.setPurchaseCount(data.getPurchaseCount() + 1);
      data.setTotalAov(data.getTotalAov().add(purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO));

      timeSeriesMap.put(periodKey, data);
    }

    return timeSeriesMap.values().stream()
        .sorted(Comparator.comparing(TimeSeriesData::getPeriodKey))
        .map(data -> {
          BigDecimal aov = data.getPurchaseCount() > 0
              ? data.getTotalAov().divide(BigDecimal.valueOf(data.getPurchaseCount()), 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
          
          // Calculate start and end time for period (simplified)
          Instant periodStart = parsePeriodKey(data.getPeriodKey(), granularity);
          Instant periodEnd = getNextPeriod(periodStart, granularity);
          
          return new TimeSeriesDataDto(data.getPeriodKey(), periodStart, periodEnd, 
              data.getRevenue(), data.getPurchaseCount(), aov);
        })
        .collect(Collectors.toList());
  }

  /**
   * Get period key for time-series grouping.
   */
  public String getPeriodKey(Instant instant, String granularity) {
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

  /**
   * Parse period key to Instant.
   */
  public Instant parsePeriodKey(String periodKey, String granularity) {
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

  /**
   * Get next period Instant.
   */
  public Instant getNextPeriod(Instant current, String granularity) {
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

  /**
   * Calculate period comparison between current and previous period.
   */
  public PeriodComparisonDto calculatePeriodComparison(List<Purchase> current, List<Purchase> previous) {
    RevenueSummaryDto currentSummary = calculateRevenueSummary(current);
    RevenueSummaryDto previousSummary = calculateRevenueSummary(previous);

    BigDecimal revenueChange = currentSummary.getTotalRevenue().subtract(previousSummary.getTotalRevenue());
    BigDecimal revenueChangePercent = previousSummary.getTotalRevenue().compareTo(BigDecimal.ZERO) > 0
        ? revenueChange.divide(previousSummary.getTotalRevenue(), 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
        : BigDecimal.ZERO;

    BigDecimal purchaseCountChange = BigDecimal.valueOf(currentSummary.getTotalPurchases() - previousSummary.getTotalPurchases());
    BigDecimal purchaseCountChangePercent = previousSummary.getTotalPurchases() > 0
        ? purchaseCountChange.divide(BigDecimal.valueOf(previousSummary.getTotalPurchases()), 4, RoundingMode.HALF_UP)
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
}

