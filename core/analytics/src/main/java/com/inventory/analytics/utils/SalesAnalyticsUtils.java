package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.*;
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
public class SalesAnalyticsUtils {

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  public List<Purchase> getCompletedPurchases(String shopId, Instant startDate, Instant endDate) {
    return purchaseRepository.findByShopId(shopId, Pageable.unpaged())
        .getContent()
        .stream()
        .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
        .filter(p -> p.getSoldAt() != null)
        .filter(p -> !p.getSoldAt().isBefore(startDate) && !p.getSoldAt().isAfter(endDate))
        .collect(Collectors.toList());
  }

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

  public List<TopProductDto> getTopProducts(List<Purchase> purchases, int limit) {
    Map<String, TopProductDto> productMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String inventoryId = item.getInventoryId();
          TopProductDto dto = productMap.getOrDefault(inventoryId,
              new TopProductDto(inventoryId, null, null, null, 0, BigDecimal.ZERO, 0));

          int qty = dto.getTotalQuantitySold() != null ? dto.getTotalQuantitySold() : 0;
          BigDecimal revenue = dto.getTotalRevenue() != null ? dto.getTotalRevenue() : BigDecimal.ZERO;
          int sales = dto.getNumberOfSales() != null ? dto.getNumberOfSales() : 0;

          dto.setTotalQuantitySold(qty + getBaseQuantity(item));
          BigDecimal pricingQuantity = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getPriceToRetail() != null
              ? item.getPriceToRetail().multiply(pricingQuantity)
              : BigDecimal.ZERO;
          dto.setTotalRevenue(revenue.add(itemRevenue));
          dto.setNumberOfSales(sales + 1);
          dto.setProductName(item.getName());

          if (dto.getLotId() == null || dto.getCompanyName() == null) {
            inventoryRepository.findById(inventoryId).ifPresent(inv -> {
              dto.setLotId(inv.getLotId());
              dto.setCompanyName(inv.getCompanyName());
            });
          }

          productMap.put(inventoryId, dto);
        }
      }
    }

    return productMap.values().stream()
        .sorted((a, b) -> (b.getTotalRevenue() != null ? b.getTotalRevenue() : BigDecimal.ZERO)
            .compareTo(a.getTotalRevenue() != null ? a.getTotalRevenue() : BigDecimal.ZERO))
        .limit(limit)
        .collect(Collectors.toList());
  }

  public List<SalesByGroupDto> getSalesByProduct(List<Purchase> purchases) {
    Map<String, SalesByGroupDto> productMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String productName = item.getName() != null ? item.getName() : "Unknown";
          SalesByGroupDto dto = productMap.getOrDefault(productName,
              new SalesByGroupDto(productName, 0, BigDecimal.ZERO, 0));

          int qty = dto.getTotalQuantitySold() != null ? dto.getTotalQuantitySold() : 0;
          BigDecimal revenue = dto.getTotalRevenue() != null ? dto.getTotalRevenue() : BigDecimal.ZERO;
          int sales = dto.getNumberOfSales() != null ? dto.getNumberOfSales() : 0;

          BigDecimal pricingQty = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getPriceToRetail() != null
              ? item.getPriceToRetail().multiply(pricingQty)
              : BigDecimal.ZERO;

          dto.setTotalQuantitySold(qty + getBaseQuantity(item));
          dto.setTotalRevenue(revenue.add(itemRevenue));
          dto.setNumberOfSales(sales + 1);
          productMap.put(productName, dto);
        }
      }
    }

    return productMap.values().stream()
        .sorted((a, b) -> (b.getTotalRevenue() != null ? b.getTotalRevenue() : BigDecimal.ZERO)
            .compareTo(a.getTotalRevenue() != null ? a.getTotalRevenue() : BigDecimal.ZERO))
        .collect(Collectors.toList());
  }

  public List<SalesByGroupDto> getSalesByLotId(List<Purchase> purchases) {
    Map<String, SalesByGroupDto> lotMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    Map<String, String> inventoryToLotId = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId)
          .ifPresent(inv -> inventoryToLotId.put(inventoryId, inv.getLotId()));
    }

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String lotId = inventoryToLotId.getOrDefault(item.getInventoryId(), "Unknown");
          SalesByGroupDto dto = lotMap.getOrDefault(lotId, new SalesByGroupDto(lotId, 0, BigDecimal.ZERO, 0));

          int qty = dto.getTotalQuantitySold() != null ? dto.getTotalQuantitySold() : 0;
          BigDecimal revenue = dto.getTotalRevenue() != null ? dto.getTotalRevenue() : BigDecimal.ZERO;
          int sales = dto.getNumberOfSales() != null ? dto.getNumberOfSales() : 0;

          BigDecimal pricingQty = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getPriceToRetail() != null
              ? item.getPriceToRetail().multiply(pricingQty)
              : BigDecimal.ZERO;

          dto.setTotalQuantitySold(qty + getBaseQuantity(item));
          dto.setTotalRevenue(revenue.add(itemRevenue));
          dto.setNumberOfSales(sales + 1);
          lotMap.put(lotId, dto);
        }
      }
    }

    return lotMap.values().stream()
        .sorted((a, b) -> (b.getTotalRevenue() != null ? b.getTotalRevenue() : BigDecimal.ZERO)
            .compareTo(a.getTotalRevenue() != null ? a.getTotalRevenue() : BigDecimal.ZERO))
        .collect(Collectors.toList());
  }

  public List<SalesByGroupDto> getSalesByCompany(List<Purchase> purchases) {
    Map<String, SalesByGroupDto> companyMap = new HashMap<>();
    Set<String> inventoryIds = new HashSet<>();

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          inventoryIds.add(item.getInventoryId());
        }
      }
    }

    Map<String, String> inventoryToCompany = new HashMap<>();
    for (String inventoryId : inventoryIds) {
      inventoryRepository.findById(inventoryId)
          .ifPresent(inv -> inventoryToCompany.put(inventoryId,
              inv.getCompanyName() != null ? inv.getCompanyName() : "Unknown"));
    }

    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String companyName = inventoryToCompany.getOrDefault(item.getInventoryId(), "Unknown");
          SalesByGroupDto dto = companyMap.getOrDefault(companyName,
              new SalesByGroupDto(companyName, 0, BigDecimal.ZERO, 0));

          int qty = dto.getTotalQuantitySold() != null ? dto.getTotalQuantitySold() : 0;
          BigDecimal revenue = dto.getTotalRevenue() != null ? dto.getTotalRevenue() : BigDecimal.ZERO;
          int sales = dto.getNumberOfSales() != null ? dto.getNumberOfSales() : 0;

          BigDecimal pricingQty = getPricingQuantity(item);
          BigDecimal itemRevenue = item.getPriceToRetail() != null
              ? item.getPriceToRetail().multiply(pricingQty)
              : BigDecimal.ZERO;

          dto.setTotalQuantitySold(qty + getBaseQuantity(item));
          dto.setTotalRevenue(revenue.add(itemRevenue));
          dto.setNumberOfSales(sales + 1);
          companyMap.put(companyName, dto);
        }
      }
    }

    return companyMap.values().stream()
        .sorted((a, b) -> (b.getTotalRevenue() != null ? b.getTotalRevenue() : BigDecimal.ZERO)
            .compareTo(a.getTotalRevenue() != null ? a.getTotalRevenue() : BigDecimal.ZERO))
        .collect(Collectors.toList());
  }

  public List<TimeSeriesDataDto> getTimeSeriesData(
      List<Purchase> purchases,
      Instant startDate,
      Instant endDate,
      String granularity) {

    Map<String, TimeSeriesDataDto> timeSeriesMap = new HashMap<>();

    for (Purchase purchase : purchases) {
      if (purchase.getSoldAt() == null) continue;

      String periodKey = getPeriodKey(purchase.getSoldAt(), granularity);
      TimeSeriesDataDto dto = timeSeriesMap.getOrDefault(periodKey,
          new TimeSeriesDataDto(periodKey, null, null, BigDecimal.ZERO, 0, null));

      BigDecimal rev = dto.getRevenue() != null ? dto.getRevenue() : BigDecimal.ZERO;
      int count = dto.getPurchaseCount() != null ? dto.getPurchaseCount() : 0;
      BigDecimal purchaseTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;

      dto.setRevenue(rev.add(purchaseTotal));
      dto.setPurchaseCount(count + 1);
      timeSeriesMap.put(periodKey, dto);
    }

    return timeSeriesMap.values().stream()
        .sorted(Comparator.comparing(TimeSeriesDataDto::getPeriod))
        .map(dto -> {
          int count = dto.getPurchaseCount() != null ? dto.getPurchaseCount() : 0;
          BigDecimal rev = dto.getRevenue() != null ? dto.getRevenue() : BigDecimal.ZERO;
          BigDecimal aov = count > 0
              ? rev.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;

          Instant periodStart = parsePeriodKey(dto.getPeriod(), granularity);
          Instant periodEnd = getNextPeriod(periodStart, granularity);

          return new TimeSeriesDataDto(dto.getPeriod(), periodStart, periodEnd, rev, count, aov);
        })
        .collect(Collectors.toList());
  }

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

  public PeriodComparisonDto calculatePeriodComparison(List<Purchase> current, List<Purchase> previous) {
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
