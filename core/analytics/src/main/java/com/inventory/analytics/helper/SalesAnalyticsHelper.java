package com.inventory.analytics.helper;

import com.inventory.analytics.rest.dto.internal.GroupSalesData;
import com.inventory.analytics.rest.dto.internal.ProductSalesData;
import com.inventory.analytics.rest.dto.internal.TimeSeriesData;
import com.inventory.analytics.rest.dto.sales.*;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
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
   */
  public List<Purchase> getCompletedPurchases(String shopId, Instant startDate, Instant endDate) {
    return purchaseRepository.findByShopId(shopId, Pageable.unpaged())
        .getContent()
        .stream()
        .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
        .filter(p -> p.getSoldAt() != null)
        .filter(p -> !p.getSoldAt().isBefore(startDate) && !p.getSoldAt().isAfter(endDate))
        .collect(Collectors.toList());
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
   * Get top products by revenue.
   */
  public List<TopProductDto> getTopProducts(List<Purchase> purchases, int limit) {
    Map<String, ProductSalesData> productMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String inventoryId = item.getInventoryId();
          ProductSalesData data = productMap.getOrDefault(inventoryId, new ProductSalesData(inventoryId));
          
          data.setQuantitySold(data.getQuantitySold() + getBaseQuantity(item));
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getSellingPrice() != null
              ? item.getSellingPrice().multiply(pricingQuantity)
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);
          data.setProductName(item.getName());

          // Get inventory details for lotId and companyName
          if (data.getLotId() == null || data.getCompanyName() == null) {
            inventoryRepository.findById(inventoryId).ifPresent(inv -> {
              data.setLotId(inv.getLotId());
              data.setCompanyName(inv.getCompanyName());
            });
          }

          productMap.put(inventoryId, data);
        }
      }
    }

    return productMap.values().stream()
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
          
          data.setQuantitySold(data.getQuantitySold() + getBaseQuantity(item));
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getSellingPrice() != null
              ? item.getSellingPrice().multiply(pricingQuantity)
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
   * Get sales grouped by lotId.
   */
  public List<SalesByGroupDto> getSalesByLotId(List<Purchase> purchases) {
    Map<String, GroupSalesData> lotMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    // Collect all inventory IDs
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    // Get lotIds from inventory
    Map<String, String> inventoryToLotId = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId)
          .ifPresent(inv -> inventoryToLotId.put(inventoryId, inv.getLotId()));
    }

    // Aggregate by lotId
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String lotId = inventoryToLotId.getOrDefault(item.getInventoryId(), "Unknown");
          GroupSalesData data = lotMap.getOrDefault(lotId, new GroupSalesData(lotId));
          
          data.setQuantitySold(data.getQuantitySold() + getBaseQuantity(item));
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getSellingPrice() != null
              ? item.getSellingPrice().multiply(pricingQuantity)
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);

          lotMap.put(lotId, data);
        }
      }
    }

    return lotMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .map(data -> new SalesByGroupDto(data.getGroupKey(), data.getQuantitySold(), data.getTotalRevenue(), data.getNumberOfSales()))
        .collect(Collectors.toList());
  }

  /**
   * Get sales grouped by company name.
   */
  public List<SalesByGroupDto> getSalesByCompany(List<Purchase> purchases) {
    Map<String, GroupSalesData> companyMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    // Collect all inventory IDs
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    // Get company names from inventory
    Map<String, String> inventoryToCompany = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId)
          .ifPresent(inv -> inventoryToCompany.put(inventoryId, 
              inv.getCompanyName() != null ? inv.getCompanyName() : "Unknown"));
    }

    // Aggregate by company
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String companyName = inventoryToCompany.getOrDefault(item.getInventoryId(), "Unknown");
          GroupSalesData data = companyMap.getOrDefault(companyName, new GroupSalesData(companyName));
          
          data.setQuantitySold(data.getQuantitySold() + getBaseQuantity(item));
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getSellingPrice() != null
              ? item.getSellingPrice().multiply(pricingQuantity)
              : BigDecimal.ZERO;
          data.setTotalRevenue(data.getTotalRevenue().add(itemRevenue));
          data.setNumberOfSales(data.getNumberOfSales() + 1);

          companyMap.put(companyName, data);
        }
      }
    }

    return companyMap.values().stream()
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .map(data -> new SalesByGroupDto(data.getGroupKey(), data.getQuantitySold(), data.getTotalRevenue(), data.getNumberOfSales()))
        .collect(Collectors.toList());
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

