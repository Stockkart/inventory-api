package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.*;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.product.domain.model.Inventory;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class VendorAnalyticsUtils {

  @Autowired
  private VendorRepository vendorRepository;

  /** The vendors of these ids, read once per request. */
  public Map<String, Vendor> vendorsById(Collection<String> vendorIds) {
    Map<String, Vendor> out = new HashMap<>();
    if (vendorIds.isEmpty()) {
      return out;
    }
    for (Vendor v : vendorRepository.findAllById(vendorIds)) {
      out.put(v.getId(), v);
    }
    return out;
  }

  /** The vendors of the shop's lots, as a set filled in first-lot order (as the tab always built it). */
  private static Set<String> lotVendorIds(VendorLotTotals totals) {
    Set<String> vendorIds = new HashSet<>();
    for (String id : totals.vendorIds()) {
      vendorIds.add(id);
    }
    return vendorIds;
  }

  /**
   * Calculate vendor stock analytics.
   */
  public List<VendorStockDto> calculateVendorStockAnalytics(
      VendorLotTotals totals,
      List<LineGroup> groups,
      Map<String, Inventory> lotsById,
      Map<String, Vendor> vendorMapById) {
    Map<String, VendorLotTotals.Stock> vendorMap = new HashMap<>();
    for (String vendorId : lotVendorIds(totals)) {
      vendorMap.put(vendorId, totals.stock.get(vendorId));
    }

    // Revenue per vendor from the period's lines
    Map<String, BigDecimal> vendorRevenueMap = new HashMap<>();
    for (LineGroup g : groups) {
      Inventory inv = g.lotId() == null ? null : lotsById.get(g.lotId());
      if (inv != null && inv.getVendorId() != null) {
        BigDecimal itemRevenue = g.line().getPriceToRetail() != null
            ? g.line().getPriceToRetail().multiply(g.pricingQuantity())
            : BigDecimal.ZERO;
        vendorRevenueMap.merge(inv.getVendorId(), g.times(itemRevenue), BigDecimal::add);
      }
    }

    // Build response
    return vendorMap.entrySet().stream()
        .map(e -> {
          String vendorId = e.getKey();
          VendorLotTotals.Stock data = e.getValue();
          Vendor vendor = vendorMapById.get(vendorId);
          BigDecimal sellThrough = BigDecimal.ZERO;
          if (data.received > 0) {
            sellThrough = BigDecimal.valueOf(data.sold)
                .divide(BigDecimal.valueOf(data.received), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          BigDecimal revenue = vendorRevenueMap.getOrDefault(vendorId, BigDecimal.ZERO);

          return new VendorStockDto(
              vendorId,
              vendor != null ? vendor.getName() : null,
              vendor != null ? vendor.getCompanyName() : null,
              data.received,
              data.sold,
              data.unsold,
              data.expired,
              sellThrough,
              revenue,
              data.unsoldValue,
              data.expiredValue,
              data.products,
              data.lotIds.size()
          );
        })
        .sorted((a, b) -> b.getTotalInventoryReceived().compareTo(a.getTotalInventoryReceived()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate vendor revenue analytics.
   */
  public List<VendorRevenueDto> calculateVendorRevenueAnalytics(
      List<LineGroup> groups, Map<String, Inventory> lotsById, Map<String, Vendor> vendorMapById) {
    Map<String, VendorRevenueData> vendorMap = new HashMap<>();
    Set<String> vendorIds = new HashSet<>();

    // Collect vendor IDs from the period's lines
    for (LineGroup g : groups) {
      Inventory inv = g.lotId() == null ? null : lotsById.get(g.lotId());
      if (inv != null && inv.getVendorId() != null) {
        vendorIds.add(inv.getVendorId());
      }
    }


    // Initialize vendor data
    for (String vendorId : vendorIds) {
      Vendor vendor = vendorMapById.get(vendorId);
      VendorRevenueData data = new VendorRevenueData(vendorId);
      if (vendor != null) {
        data.vendorName = vendor.getName();
        data.vendorCompanyName = vendor.getCompanyName();
      }
      vendorMap.put(vendorId, data);
    }

    // Process the period's lines (billIds: the distinct bills each group appears on)
    for (LineGroup g : groups) {
      Inventory inv = g.lotId() == null ? null : lotsById.get(g.lotId());
      if (inv == null || inv.getVendorId() == null) continue;

      VendorRevenueData data = vendorMap.get(inv.getVendorId());
      if (data == null) continue;

      int quantity = g.baseQuantity();
      BigDecimal pricingQuantity = g.pricingQuantity();
      BigDecimal priceToRetail = g.line().getPriceToRetail() != null ? g.line().getPriceToRetail() : BigDecimal.ZERO;
      BigDecimal costPrice = inv.costForValuation() != null ? inv.costForValuation() : BigDecimal.ZERO;

      BigDecimal itemRevenue = priceToRetail.multiply(pricingQuantity);
      BigDecimal itemCost = costPrice.multiply(pricingQuantity);
      BigDecimal itemProfit = itemRevenue.subtract(itemCost);

      data.totalRevenue = data.totalRevenue.add(g.times(itemRevenue));
      data.totalCost = data.totalCost.add(g.times(itemCost));
      data.grossProfit = data.grossProfit.add(g.times(itemProfit));
      data.totalItemsSold += g.times(quantity);
      data.purchaseIds.addAll(g.billIds());
    }

    // Build response
    return vendorMap.values().stream()
        .map(data -> {
          BigDecimal marginPercent = BigDecimal.ZERO;
          if (data.totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = data.grossProfit
                .divide(data.totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new VendorRevenueDto(
              data.vendorId,
              data.vendorName,
              data.vendorCompanyName,
              data.totalRevenue,
              data.totalCost,
              data.grossProfit,
              marginPercent,
              data.totalItemsSold,
              data.purchaseIds.size()
          );
        })
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate vendor performance analytics.
   */
  public List<VendorPerformanceDto> calculateVendorPerformanceAnalytics(
      VendorLotTotals totals, Map<String, Vendor> vendorMapById) {
    Map<String, VendorLotTotals.Performance> vendorMap = new HashMap<>();
    for (String vendorId : lotVendorIds(totals)) {
      vendorMap.put(vendorId, totals.performance.get(vendorId));
    }

    // Build response
    return vendorMap.entrySet().stream()
        .map(e -> {
          String vendorId = e.getKey();
          VendorLotTotals.Performance data = e.getValue();
          Vendor vendor = vendorMapById.get(vendorId);
          BigDecimal avgDaysInStock = BigDecimal.ZERO;
          if (data.itemsForDays > 0) {
            avgDaysInStock = BigDecimal.valueOf(data.daysInStock)
                .divide(BigDecimal.valueOf(data.itemsForDays), 2, RoundingMode.HALF_UP);
          }

          BigDecimal fastMovingPercent = BigDecimal.ZERO;
          if (data.received > 0) {
            fastMovingPercent = BigDecimal.valueOf(data.fastMoving)
                .divide(BigDecimal.valueOf(data.received), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          BigDecimal expiryLossPercent = BigDecimal.ZERO;
          if (data.received > 0) {
            expiryLossPercent = BigDecimal.valueOf(data.expiredItems)
                .divide(BigDecimal.valueOf(data.received), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          // Calculate risk score (0-100)
          // Higher expiry %, dead stock, and longer days in stock = higher risk
          BigDecimal riskScore = BigDecimal.ZERO;
          if (data.received > 0) {
            BigDecimal expiryRisk = expiryLossPercent.multiply(BigDecimal.valueOf(0.4)); // 40% weight
            BigDecimal deadStockRisk = data.received > 0
                ? BigDecimal.valueOf(data.deadStockItems)
                    .divide(BigDecimal.valueOf(data.received), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(0.3)) // 30% weight
                : BigDecimal.ZERO;
            BigDecimal daysRisk = avgDaysInStock.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE).multiply(BigDecimal.valueOf(0.3)); // 30% weight, capped at 100 days
            riskScore = expiryRisk.add(deadStockRisk).add(daysRisk).min(BigDecimal.valueOf(100));
          }

          String riskLevel = "LOW";
          if (riskScore.compareTo(BigDecimal.valueOf(70)) >= 0) {
            riskLevel = "HIGH";
          } else if (riskScore.compareTo(BigDecimal.valueOf(40)) >= 0) {
            riskLevel = "MEDIUM";
          }

          return new VendorPerformanceDto(
              vendorId,
              vendor != null ? vendor.getName() : null,
              vendor != null ? vendor.getCompanyName() : null,
              avgDaysInStock,
              fastMovingPercent,
              data.deadStockValue,
              data.expiredValue,
              expiryLossPercent,
              data.expiredItems,
              data.deadStockItems,
              riskScore,
              riskLevel
          );
        })
        .sorted((a, b) -> b.getRiskScore().compareTo(a.getRiskScore()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate category expiry analytics per vendor.
   */
  public List<VendorCategoryExpiryDto> calculateCategoryExpiryAnalytics(
      VendorLotTotals totals, Map<String, Vendor> vendorMapById) {
    Map<String, VendorLotTotals.Category> categoryMap = new HashMap<>();
    totals.categories.forEach(categoryMap::put); // first-lot order, as the map was always filled

    // Build response
    return categoryMap.values().stream()
        .map(data -> {
          Vendor vendor = vendorMapById.get(data.vendorId);
          BigDecimal expiryPercent = BigDecimal.ZERO;
          if (data.received > 0) {
            expiryPercent = BigDecimal.valueOf(data.expired)
                .divide(BigDecimal.valueOf(data.received), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new VendorCategoryExpiryDto(
              data.vendorId,
              vendor != null ? vendor.getName() : null,
              data.businessType,
              data.received,
              data.expired,
              expiryPercent,
              data.expiredValue
          );
        })
        .sorted((a, b) -> b.getExpiredStockValue().compareTo(a.getExpiredStockValue()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate vendor dependency analytics.
   */
  public List<VendorDependencyDto> calculateVendorDependencyAnalytics(
      List<VendorRevenueDto> vendorRevenues, VendorLotTotals totals) {
    // Calculate total revenue
    BigDecimal totalRevenue = vendorRevenues.stream()
        .map(VendorRevenueDto::getTotalRevenue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalInventoryValue = totals.totalValue();

    // Build dependency analytics
    return vendorRevenues.stream()
        .map(revenue -> {
          BigDecimal revenuePercent = BigDecimal.ZERO;
          if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            revenuePercent = revenue.getTotalRevenue()
                .divide(totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          BigDecimal inventoryValue = totals.valueByVendor.getOrDefault(revenue.getVendorId(), BigDecimal.ZERO);

          BigDecimal inventoryPercent = BigDecimal.ZERO;
          if (totalInventoryValue.compareTo(BigDecimal.ZERO) > 0) {
            inventoryPercent = inventoryValue
                .divide(totalInventoryValue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          // Dependency score: weighted average of revenue % and inventory %
          BigDecimal dependencyScore = revenuePercent.multiply(BigDecimal.valueOf(0.6))
              .add(inventoryPercent.multiply(BigDecimal.valueOf(0.4)));

          String dependencyLevel = "LOW";
          if (dependencyScore.compareTo(BigDecimal.valueOf(50)) >= 0) {
            dependencyLevel = "CRITICAL";
          } else if (dependencyScore.compareTo(BigDecimal.valueOf(30)) >= 0) {
            dependencyLevel = "HIGH";
          } else if (dependencyScore.compareTo(BigDecimal.valueOf(15)) >= 0) {
            dependencyLevel = "MEDIUM";
          }

          VendorLotTotals.Stock stock = totals.stock.get(revenue.getVendorId());
          return new VendorDependencyDto(
              revenue.getVendorId(),
              revenue.getVendorName(),
              revenue.getVendorCompanyName(),
              revenuePercent,
              inventoryPercent,
              stock != null ? stock.products : 0,
              dependencyScore,
              dependencyLevel
          );
        })
        .sorted((a, b) -> b.getDependencyScore().compareTo(a.getDependencyScore()))
        .collect(Collectors.toList());
  }

  private static class VendorRevenueData {
    String vendorId;
    String vendorName;
    String vendorCompanyName;
    BigDecimal totalRevenue = BigDecimal.ZERO;
    BigDecimal totalCost = BigDecimal.ZERO;
    BigDecimal grossProfit = BigDecimal.ZERO;
    int totalItemsSold = 0;
    Set<String> purchaseIds = new HashSet<>();

    VendorRevenueData(String vendorId) {
      this.vendorId = vendorId;
    }
  }
}
