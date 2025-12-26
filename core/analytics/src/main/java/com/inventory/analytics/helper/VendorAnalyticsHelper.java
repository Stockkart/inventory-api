package com.inventory.analytics.helper;

import com.inventory.analytics.rest.dto.vendor.*;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class VendorAnalyticsHelper {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private VendorRepository vendorRepository;

  /**
   * Calculate vendor stock analytics.
   */
  public List<VendorStockDto> calculateVendorStockAnalytics(String shopId, List<Inventory> allInventories, List<Purchase> purchases) {
    Map<String, VendorStockData> vendorMap = new HashMap<>();
    Set<String> vendorIds = new HashSet<>();

    // Collect all vendor IDs
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() != null) {
        vendorIds.add(inventory.getVendorId());
      }
    }

    // Get vendor details
    Map<String, Vendor> vendorMapById = new HashMap<>();
    for (String vendorId : vendorIds) {
      vendorRepository.findById(vendorId).ifPresent(vendor -> vendorMapById.put(vendorId, vendor));
    }

    // Initialize vendor data
    for (String vendorId : vendorIds) {
      Vendor vendor = vendorMapById.get(vendorId);
      VendorStockData data = new VendorStockData(vendorId);
      if (vendor != null) {
        data.vendorName = vendor.getName();
        data.vendorCompanyName = vendor.getCompanyName();
      }
      vendorMap.put(vendorId, data);
    }

    // Process inventory
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() == null) continue;

      VendorStockData data = vendorMap.get(inventory.getVendorId());
      if (data == null) continue;

      Integer received = inventory.getReceivedCount() != null ? inventory.getReceivedCount() : 0;
      Integer sold = inventory.getSoldCount() != null ? inventory.getSoldCount() : 0;
      Integer current = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
      BigDecimal costPrice = inventory.getCostPrice() != null ? inventory.getCostPrice() : BigDecimal.ZERO;

      data.totalInventoryReceived += received;
      data.totalQuantitySold += sold;
      data.totalUnsoldStock += current;

      // Check if expired
      if (inventory.getExpiryDate() != null && inventory.getExpiryDate().isBefore(Instant.now())) {
        data.totalExpiredStock += current;
        data.expiredStockValue = data.expiredStockValue.add(costPrice.multiply(BigDecimal.valueOf(current)));
      }

      data.unsoldStockValue = data.unsoldStockValue.add(costPrice.multiply(BigDecimal.valueOf(current)));
      data.numberOfProducts++;
      if (inventory.getLotId() != null) {
        data.lotIds.add(inventory.getLotId());
      }
    }

    // Process purchases to get revenue
    Map<String, BigDecimal> vendorRevenueMap = new HashMap<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          Inventory inv = inventoryRepository.findById(item.getInventoryId()).orElse(null);
          if (inv != null && inv.getVendorId() != null) {
            BigDecimal itemRevenue = item.getSellingPrice() != null && item.getQuantity() != null
                ? item.getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                : BigDecimal.ZERO;
            vendorRevenueMap.merge(inv.getVendorId(), itemRevenue, BigDecimal::add);
          }
        }
      }
    }

    // Build response
    return vendorMap.values().stream()
        .map(data -> {
          BigDecimal sellThrough = BigDecimal.ZERO;
          if (data.totalInventoryReceived > 0) {
            sellThrough = BigDecimal.valueOf(data.totalQuantitySold)
                .divide(BigDecimal.valueOf(data.totalInventoryReceived), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          BigDecimal revenue = vendorRevenueMap.getOrDefault(data.vendorId, BigDecimal.ZERO);

          return new VendorStockDto(
              data.vendorId,
              data.vendorName,
              data.vendorCompanyName,
              data.totalInventoryReceived,
              data.totalQuantitySold,
              data.totalUnsoldStock,
              data.totalExpiredStock,
              sellThrough,
              revenue,
              data.unsoldStockValue,
              data.expiredStockValue,
              data.numberOfProducts,
              data.lotIds.size()
          );
        })
        .sorted((a, b) -> b.getTotalInventoryReceived().compareTo(a.getTotalInventoryReceived()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate vendor revenue analytics.
   */
  public List<VendorRevenueDto> calculateVendorRevenueAnalytics(String shopId, List<Purchase> purchases) {
    Map<String, VendorRevenueData> vendorMap = new HashMap<>();
    Set<String> vendorIds = new HashSet<>();

    // Collect vendor IDs from purchases
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          Inventory inv = inventoryRepository.findById(item.getInventoryId()).orElse(null);
          if (inv != null && inv.getVendorId() != null) {
            vendorIds.add(inv.getVendorId());
          }
        }
      }
    }

    // Get vendor details
    Map<String, Vendor> vendorMapById = new HashMap<>();
    for (String vendorId : vendorIds) {
      vendorRepository.findById(vendorId).ifPresent(vendor -> vendorMapById.put(vendorId, vendor));
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

    // Process purchases
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          Inventory inv = inventoryRepository.findById(item.getInventoryId()).orElse(null);
          if (inv == null || inv.getVendorId() == null) continue;

          VendorRevenueData data = vendorMap.get(inv.getVendorId());
          if (data == null) continue;

          Integer quantity = item.getQuantity() != null ? item.getQuantity() : 0;
          BigDecimal sellingPrice = item.getSellingPrice() != null ? item.getSellingPrice() : BigDecimal.ZERO;
          BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;

          BigDecimal itemRevenue = sellingPrice.multiply(BigDecimal.valueOf(quantity));
          BigDecimal itemCost = costPrice.multiply(BigDecimal.valueOf(quantity));
          BigDecimal itemProfit = itemRevenue.subtract(itemCost);

          data.totalRevenue = data.totalRevenue.add(itemRevenue);
          data.totalCost = data.totalCost.add(itemCost);
          data.grossProfit = data.grossProfit.add(itemProfit);
          data.totalItemsSold += quantity;
          data.purchaseIds.add(purchase.getId());
        }
      }
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
  public List<VendorPerformanceDto> calculateVendorPerformanceAnalytics(String shopId, List<Inventory> allInventories) {
    Map<String, VendorPerformanceData> vendorMap = new HashMap<>();
    Set<String> vendorIds = new HashSet<>();

    // Collect vendor IDs
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() != null) {
        vendorIds.add(inventory.getVendorId());
      }
    }

    // Get vendor details
    Map<String, Vendor> vendorMapById = new HashMap<>();
    for (String vendorId : vendorIds) {
      vendorRepository.findById(vendorId).ifPresent(vendor -> vendorMapById.put(vendorId, vendor));
    }

    // Initialize vendor data
    for (String vendorId : vendorIds) {
      Vendor vendor = vendorMapById.get(vendorId);
      VendorPerformanceData data = new VendorPerformanceData(vendorId);
      if (vendor != null) {
        data.vendorName = vendor.getName();
        data.vendorCompanyName = vendor.getCompanyName();
      }
      vendorMap.put(vendorId, data);
    }

    Instant now = Instant.now();

    // Process inventory
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() == null) continue;

      VendorPerformanceData data = vendorMap.get(inventory.getVendorId());
      if (data == null) continue;

      Integer received = inventory.getReceivedCount() != null ? inventory.getReceivedCount() : 0;
      Integer sold = inventory.getSoldCount() != null ? inventory.getSoldCount() : 0;
      Integer current = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
      BigDecimal costPrice = inventory.getCostPrice() != null ? inventory.getCostPrice() : BigDecimal.ZERO;

      // Calculate days in stock
      if (inventory.getReceivedDate() != null) {
        long daysInStock = ChronoUnit.DAYS.between(inventory.getReceivedDate(), now);
        data.totalDaysInStock += daysInStock * received;
        data.totalItemsForDays += received;
      }

      // Check for expired items
      if (inventory.getExpiryDate() != null && inventory.getExpiryDate().isBefore(now)) {
        data.totalExpiredItems += current;
        data.expiredStockValue = data.expiredStockValue.add(costPrice.multiply(BigDecimal.valueOf(current)));
      }

      // Dead stock (unsold for > 90 days)
      if (inventory.getReceivedDate() != null) {
        long daysSinceReceived = ChronoUnit.DAYS.between(inventory.getReceivedDate(), now);
        if (daysSinceReceived > 90 && current > 0) {
          data.totalDeadStockItems += current;
          data.deadStockValue = data.deadStockValue.add(costPrice.multiply(BigDecimal.valueOf(current)));
        }
      }

      // Fast moving items (sold within 30 days)
      if (inventory.getReceivedDate() != null && sold > 0) {
        long daysToSell = ChronoUnit.DAYS.between(inventory.getReceivedDate(), now);
        if (daysToSell <= 30) {
          data.fastMovingItems += sold;
        }
      }

      data.totalReceived += received;
    }

    // Build response
    return vendorMap.values().stream()
        .map(data -> {
          BigDecimal avgDaysInStock = BigDecimal.ZERO;
          if (data.totalItemsForDays > 0) {
            avgDaysInStock = BigDecimal.valueOf(data.totalDaysInStock)
                .divide(BigDecimal.valueOf(data.totalItemsForDays), 2, RoundingMode.HALF_UP);
          }

          BigDecimal fastMovingPercent = BigDecimal.ZERO;
          if (data.totalReceived > 0) {
            fastMovingPercent = BigDecimal.valueOf(data.fastMovingItems)
                .divide(BigDecimal.valueOf(data.totalReceived), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          BigDecimal expiryLossPercent = BigDecimal.ZERO;
          if (data.totalReceived > 0) {
            expiryLossPercent = BigDecimal.valueOf(data.totalExpiredItems)
                .divide(BigDecimal.valueOf(data.totalReceived), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          // Calculate risk score (0-100)
          // Higher expiry %, dead stock, and longer days in stock = higher risk
          BigDecimal riskScore = BigDecimal.ZERO;
          if (data.totalReceived > 0) {
            BigDecimal expiryRisk = expiryLossPercent.multiply(BigDecimal.valueOf(0.4)); // 40% weight
            BigDecimal deadStockRisk = data.totalReceived > 0
                ? BigDecimal.valueOf(data.totalDeadStockItems)
                    .divide(BigDecimal.valueOf(data.totalReceived), 4, RoundingMode.HALF_UP)
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
              data.vendorId,
              data.vendorName,
              data.vendorCompanyName,
              avgDaysInStock,
              fastMovingPercent,
              data.deadStockValue,
              data.expiredStockValue,
              expiryLossPercent,
              data.totalExpiredItems,
              data.totalDeadStockItems,
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
  public List<VendorCategoryExpiryDto> calculateCategoryExpiryAnalytics(String shopId, List<Inventory> allInventories) {
    Map<String, CategoryExpiryData> categoryMap = new HashMap<>();
    Set<String> vendorIds = new HashSet<>();

    // Collect vendor IDs
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() != null) {
        vendorIds.add(inventory.getVendorId());
      }
    }

    // Get vendor details
    Map<String, Vendor> vendorMapById = new HashMap<>();
    for (String vendorId : vendorIds) {
      vendorRepository.findById(vendorId).ifPresent(vendor -> vendorMapById.put(vendorId, vendor));
    }

    Instant now = Instant.now();

    // Process inventory
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() == null || inventory.getBusinessType() == null) continue;

      String key = inventory.getVendorId() + "|" + inventory.getBusinessType();
      CategoryExpiryData data = categoryMap.getOrDefault(key, new CategoryExpiryData(
          inventory.getVendorId(),
          inventory.getBusinessType()
      ));

      Vendor vendor = vendorMapById.get(inventory.getVendorId());
      if (vendor != null) {
        data.vendorName = vendor.getName();
      }

      Integer received = inventory.getReceivedCount() != null ? inventory.getReceivedCount() : 0;
      Integer current = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
      BigDecimal costPrice = inventory.getCostPrice() != null ? inventory.getCostPrice() : BigDecimal.ZERO;

      data.totalReceived += received;

      if (inventory.getExpiryDate() != null && inventory.getExpiryDate().isBefore(now)) {
        data.totalExpired += current;
        data.expiredStockValue = data.expiredStockValue.add(costPrice.multiply(BigDecimal.valueOf(current)));
      }

      categoryMap.put(key, data);
    }

    // Build response
    return categoryMap.values().stream()
        .map(data -> {
          BigDecimal expiryPercent = BigDecimal.ZERO;
          if (data.totalReceived > 0) {
            expiryPercent = BigDecimal.valueOf(data.totalExpired)
                .divide(BigDecimal.valueOf(data.totalReceived), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          return new VendorCategoryExpiryDto(
              data.vendorId,
              data.vendorName,
              data.businessType,
              data.totalReceived,
              data.totalExpired,
              expiryPercent,
              data.expiredStockValue
          );
        })
        .sorted((a, b) -> b.getExpiredStockValue().compareTo(a.getExpiredStockValue()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate vendor dependency analytics.
   */
  public List<VendorDependencyDto> calculateVendorDependencyAnalytics(String shopId, List<VendorRevenueDto> vendorRevenues, List<Inventory> allInventories) {
    // Calculate total revenue
    BigDecimal totalRevenue = vendorRevenues.stream()
        .map(VendorRevenueDto::getTotalRevenue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Calculate total inventory value
    BigDecimal totalInventoryValue = allInventories.stream()
        .map(inv -> {
          Integer current = inv.getCurrentCount() != null ? inv.getCurrentCount() : 0;
          BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
          return costPrice.multiply(BigDecimal.valueOf(current));
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Count products per vendor
    Map<String, Integer> vendorProductCount = new HashMap<>();
    for (Inventory inventory : allInventories) {
      if (inventory.getVendorId() != null) {
        vendorProductCount.merge(inventory.getVendorId(), 1, Integer::sum);
      }
    }

    // Build dependency analytics
    return vendorRevenues.stream()
        .map(revenue -> {
          BigDecimal revenuePercent = BigDecimal.ZERO;
          if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            revenuePercent = revenue.getTotalRevenue()
                .divide(totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }

          BigDecimal inventoryValue = allInventories.stream()
              .filter(inv -> revenue.getVendorId().equals(inv.getVendorId()))
              .map(inv -> {
                Integer current = inv.getCurrentCount() != null ? inv.getCurrentCount() : 0;
                BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
                return costPrice.multiply(BigDecimal.valueOf(current));
              })
              .reduce(BigDecimal.ZERO, BigDecimal::add);

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

          return new VendorDependencyDto(
              revenue.getVendorId(),
              revenue.getVendorName(),
              revenue.getVendorCompanyName(),
              revenuePercent,
              inventoryPercent,
              vendorProductCount.getOrDefault(revenue.getVendorId(), 0),
              dependencyScore,
              dependencyLevel
          );
        })
        .sorted((a, b) -> b.getDependencyScore().compareTo(a.getDependencyScore()))
        .collect(Collectors.toList());
  }

  // Helper classes
  private static class VendorStockData {
    String vendorId;
    String vendorName;
    String vendorCompanyName;
    int totalInventoryReceived = 0;
    int totalQuantitySold = 0;
    int totalUnsoldStock = 0;
    int totalExpiredStock = 0;
    BigDecimal unsoldStockValue = BigDecimal.ZERO;
    BigDecimal expiredStockValue = BigDecimal.ZERO;
    int numberOfProducts = 0;
    Set<String> lotIds = new HashSet<>();

    VendorStockData(String vendorId) {
      this.vendorId = vendorId;
    }
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

  private static class VendorPerformanceData {
    String vendorId;
    String vendorName;
    String vendorCompanyName;
    long totalDaysInStock = 0;
    int totalItemsForDays = 0;
    int fastMovingItems = 0;
    int totalReceived = 0;
    int totalExpiredItems = 0;
    int totalDeadStockItems = 0;
    BigDecimal deadStockValue = BigDecimal.ZERO;
    BigDecimal expiredStockValue = BigDecimal.ZERO;

    VendorPerformanceData(String vendorId) {
      this.vendorId = vendorId;
    }
  }

  private static class CategoryExpiryData {
    String vendorId;
    String vendorName;
    String businessType;
    int totalReceived = 0;
    int totalExpired = 0;
    BigDecimal expiredStockValue = BigDecimal.ZERO;

    CategoryExpiryData(String vendorId, String businessType) {
      this.vendorId = vendorId;
      this.businessType = businessType;
    }
  }
}

