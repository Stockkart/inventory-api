package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.rest.dto.response.DashboardResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(readOnly = true)
public class DashboardService {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private PurchaseRepository purchaseRepository;

  private static final int LOW_STOCK_THRESHOLD = 10; // Default threshold for low stock

  public DashboardResponse getDashboard(String shopId) {
    try {
      log.debug("Fetching dashboard data for shop: {}", shopId);

      // Get all inventory and purchases for the shop
      List<Inventory> allInventory = inventoryRepository.findByShopId(shopId);
      List<Purchase> allPurchases = purchaseRepository.findByShopId(shopId,
          org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE))
          .getContent();

      // Filter completed purchases
      List<Purchase> completedPurchases = allPurchases.stream()
          .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
          .toList();

      // Calculate key metrics
      DashboardResponse.KeyMetrics keyMetrics = calculateKeyMetrics(allInventory, completedPurchases);

      // Get low stock items
      List<DashboardResponse.LowStockItem> lowStockItems = getLowStockItems(allInventory);

      // Calculate revenue breakdown
      DashboardResponse.RevenueBreakdown revenueBreakdown = calculateRevenueBreakdown(completedPurchases);

      // Calculate product insights
      DashboardResponse.ProductInsights productInsights = calculateProductInsights(allInventory);

      // Calculate sales trend
      DashboardResponse.SalesTrend salesTrend = calculateSalesTrend(completedPurchases);

      DashboardResponse response = new DashboardResponse();
      response.setKeyMetrics(keyMetrics);
      response.setLowStockItems(lowStockItems);
      response.setRevenueBreakdown(revenueBreakdown);
      response.setProductInsights(productInsights);
      response.setSalesTrend(salesTrend);

      log.debug("Successfully fetched dashboard data for shop: {}", shopId);
      return response;

    } catch (DataAccessException e) {
      log.error("Database error while fetching dashboard data for shop: {}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error fetching dashboard data");
    } catch (Exception e) {
      log.error("Unexpected error while fetching dashboard data for shop: {}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  private DashboardResponse.KeyMetrics calculateKeyMetrics(List<Inventory> allInventory, 
                                                           List<Purchase> completedPurchases) {
    // Total Products: Count distinct products (by lotId)
    long totalProducts = allInventory.stream()
        .map(Inventory::getLotId)
        .filter(lotId -> lotId != null && !lotId.trim().isEmpty())
        .distinct()
        .count();

    // Get today's date range
    LocalDate today = LocalDate.now();
    Instant startOfToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant endOfToday = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

    // Total Revenue Today
    BigDecimal totalRevenueToday = completedPurchases.stream()
        .filter(p -> p.getSoldAt() != null && 
                     p.getSoldAt().isAfter(startOfToday) && 
                     p.getSoldAt().isBefore(endOfToday))
        .map(Purchase::getGrandTotal)
        .filter(total -> total != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Orders Today
    long ordersToday = completedPurchases.stream()
        .filter(p -> p.getSoldAt() != null && 
                     p.getSoldAt().isAfter(startOfToday) && 
                     p.getSoldAt().isBefore(endOfToday))
        .count();

    // Low Stock Items Count
    long lowStockItemsCount = allInventory.stream()
        .filter(inv -> getCurrentBaseCount(inv) <= LOW_STOCK_THRESHOLD)
        .count();

    // Average Order Value
    BigDecimal averageOrderValue = BigDecimal.ZERO;
    if (ordersToday > 0) {
      averageOrderValue = totalRevenueToday.divide(BigDecimal.valueOf(ordersToday), 2, RoundingMode.HALF_UP);
    }

    // Total Customers (distinct customer IDs from completed purchases)
    long totalCustomers = completedPurchases.stream()
        .map(Purchase::getCustomerId)
        .filter(customerId -> customerId != null && !customerId.trim().isEmpty())
        .distinct()
        .count();

    // Total Revenue All Time
    BigDecimal totalRevenueAllTime = completedPurchases.stream()
        .map(Purchase::getGrandTotal)
        .filter(total -> total != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Total Orders All Time
    long totalOrdersAllTime = completedPurchases.size();

    DashboardResponse.KeyMetrics metrics = new DashboardResponse.KeyMetrics();
    metrics.setTotalProducts(totalProducts);
    metrics.setTotalRevenueToday(totalRevenueToday);
    metrics.setOrdersToday(ordersToday);
    metrics.setLowStockItemsCount(lowStockItemsCount);
    metrics.setAverageOrderValue(averageOrderValue);
    metrics.setTotalCustomers(totalCustomers);
    metrics.setTotalRevenueAllTime(totalRevenueAllTime);
    metrics.setTotalOrdersAllTime(totalOrdersAllTime);

    return metrics;
  }

  private List<DashboardResponse.LowStockItem> getLowStockItems(List<Inventory> allInventory) {
    return allInventory.stream()
        .filter(inv -> getCurrentBaseCount(inv) <= LOW_STOCK_THRESHOLD)
        .sorted(Comparator.comparingInt(this::getCurrentBaseCount))
        .limit(20) // Return top 20 low stock items
        .map(inv -> {
          DashboardResponse.LowStockItem item = new DashboardResponse.LowStockItem();
          item.setInventoryId(inv.getId());
          item.setName(inv.getName());
          item.setCurrentCount(getCurrentBaseCount(inv));
          item.setThreshold(LOW_STOCK_THRESHOLD);
          item.setLotId(inv.getLotId());
          item.setBarcode(inv.getBarcode());
          return item;
        })
        .collect(Collectors.toList());
  }

  private DashboardResponse.RevenueBreakdown calculateRevenueBreakdown(List<Purchase> completedPurchases) {
    LocalDate today = LocalDate.now();
    LocalDate yesterday = today.minusDays(1);
    LocalDate weekStart = today.minusDays(6); // Last 7 days including today
    LocalDate monthStart = today.withDayOfMonth(1); // First day of current month

    Instant startOfToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant endOfToday = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant startOfYesterday = yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant endOfYesterday = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant startOfWeek = weekStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant startOfMonth = monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant();

    BigDecimal todayRevenue = completedPurchases.stream()
        .filter(p -> p.getSoldAt() != null && 
                     p.getSoldAt().isAfter(startOfToday) && 
                     p.getSoldAt().isBefore(endOfToday))
        .map(Purchase::getGrandTotal)
        .filter(total -> total != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal yesterdayRevenue = completedPurchases.stream()
        .filter(p -> p.getSoldAt() != null && 
                     p.getSoldAt().isAfter(startOfYesterday) && 
                     p.getSoldAt().isBefore(endOfYesterday))
        .map(Purchase::getGrandTotal)
        .filter(total -> total != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal thisWeekRevenue = completedPurchases.stream()
        .filter(p -> p.getSoldAt() != null && p.getSoldAt().isAfter(startOfWeek))
        .map(Purchase::getGrandTotal)
        .filter(total -> total != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal thisMonthRevenue = completedPurchases.stream()
        .filter(p -> p.getSoldAt() != null && p.getSoldAt().isAfter(startOfMonth))
        .map(Purchase::getGrandTotal)
        .filter(total -> total != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Calculate percentage change from yesterday
    BigDecimal percentageChangeToday = BigDecimal.ZERO;
    if (yesterdayRevenue.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal change = todayRevenue.subtract(yesterdayRevenue);
      percentageChangeToday = change.divide(yesterdayRevenue, 4, RoundingMode.HALF_UP)
          .multiply(BigDecimal.valueOf(100))
          .setScale(2, RoundingMode.HALF_UP);
    } else if (todayRevenue.compareTo(BigDecimal.ZERO) > 0) {
      percentageChangeToday = BigDecimal.valueOf(100); // 100% increase from 0
    }

    DashboardResponse.RevenueBreakdown breakdown = new DashboardResponse.RevenueBreakdown();
    breakdown.setToday(todayRevenue);
    breakdown.setYesterday(yesterdayRevenue);
    breakdown.setThisWeek(thisWeekRevenue);
    breakdown.setThisMonth(thisMonthRevenue);
    breakdown.setPercentageChangeToday(percentageChangeToday);

    return breakdown;
  }

  private DashboardResponse.ProductInsights calculateProductInsights(List<Inventory> allInventory) {
    LocalDate today = LocalDate.now();
    LocalDate weekStart = today.minusDays(6);
    LocalDate monthStart = today.withDayOfMonth(1);

    Instant startOfToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant endOfToday = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant startOfWeek = weekStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
    Instant startOfMonth = monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant();

    // Total unique products (by lotId)
    long totalUniqueProducts = allInventory.stream()
        .map(Inventory::getLotId)
        .filter(lotId -> lotId != null && !lotId.trim().isEmpty())
        .distinct()
        .count();

    // Products added today
    long productsAddedToday = allInventory.stream()
        .filter(inv -> inv.getCreatedAt() != null && 
                       inv.getCreatedAt().isAfter(startOfToday) && 
                       inv.getCreatedAt().isBefore(endOfToday))
        .count();

    // Products added this week
    long productsAddedThisWeek = allInventory.stream()
        .filter(inv -> inv.getCreatedAt() != null && inv.getCreatedAt().isAfter(startOfWeek))
        .count();

    // Products added this month
    long productsAddedThisMonth = allInventory.stream()
        .filter(inv -> inv.getCreatedAt() != null && inv.getCreatedAt().isAfter(startOfMonth))
        .count();

    // Out of stock items
    long outOfStockItems = allInventory.stream()
        .filter(inv -> getCurrentBaseCount(inv) == 0)
        .count();

    DashboardResponse.ProductInsights insights = new DashboardResponse.ProductInsights();
    insights.setTotalUniqueProducts(totalUniqueProducts);
    insights.setProductsAddedToday(productsAddedToday);
    insights.setProductsAddedThisWeek(productsAddedThisWeek);
    insights.setProductsAddedThisMonth(productsAddedThisMonth);
    insights.setOutOfStockItems(outOfStockItems);

    return insights;
  }

  private DashboardResponse.SalesTrend calculateSalesTrend(List<Purchase> completedPurchases) {
    LocalDate today = LocalDate.now();
    List<DashboardResponse.DailySales> last7Days = new ArrayList<>();

    BigDecimal bestDayRevenue = BigDecimal.ZERO;
    String bestDayDate = null;

    // Calculate sales for each of the last 7 days
    for (int i = 6; i >= 0; i--) {
      LocalDate date = today.minusDays(i);
      Instant startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant();
      Instant endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

      List<Purchase> dayPurchases = completedPurchases.stream()
          .filter(p -> p.getSoldAt() != null && 
                       p.getSoldAt().isAfter(startOfDay) && 
                       p.getSoldAt().isBefore(endOfDay))
          .toList();

      BigDecimal dayRevenue = dayPurchases.stream()
          .map(Purchase::getGrandTotal)
          .filter(total -> total != null)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      long orderCount = dayPurchases.size();

      DashboardResponse.DailySales dailySales = new DashboardResponse.DailySales();
      dailySales.setDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
      dailySales.setRevenue(dayRevenue);
      dailySales.setOrderCount(orderCount);
      last7Days.add(dailySales);

      // Track best day
      if (dayRevenue.compareTo(bestDayRevenue) > 0) {
        bestDayRevenue = dayRevenue;
        bestDayDate = dailySales.getDate();
      }
    }

    DashboardResponse.SalesTrend trend = new DashboardResponse.SalesTrend();
    trend.setLast7Days(last7Days);
    trend.setBestDayRevenue(bestDayRevenue);
    trend.setBestDayDate(bestDayDate);

    return trend;
  }

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() == null) {
      return 0;
    }
    int factor = 1;
    if (inventory.getUnitConversions() != null
        && inventory.getUnitConversions().getFactor() != null
        && inventory.getUnitConversions().getFactor() > 0) {
      factor = inventory.getUnitConversions().getFactor();
    }
    return inventory.getCurrentCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }
}

