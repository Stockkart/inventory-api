package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.product.mapper.DashboardMapper;
import com.inventory.product.rest.dto.response.DashboardResponse;
import com.inventory.product.service.DashboardQueries.DayTotal;
import com.inventory.product.service.DashboardQueries.SalesSummary;
import com.inventory.product.service.DashboardQueries.StockSummary;
import com.inventory.product.utils.FinancialYear;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Home-screen numbers. MongoDB adds them up ({@link DashboardQueries}: one aggregation over sales,
 * one over lots); this class only turns the totals into the response. Days are India days
 * (Asia/Kolkata), so "today" starts at midnight IST, not 05:30 IST as it did with the server's UTC
 * clock.
 */
@Service
@Slf4j
public class DashboardService {

  static final ZoneId ZONE = FinancialYear.IST;
  private static final int LOW_STOCK_THRESHOLD = 10;
  private static final int LOW_STOCK_LIST_SIZE = 20;

  private final DashboardQueries queries;
  private final DashboardMapper dashboardMapper;
  private final Clock clock;

  @Autowired
  public DashboardService(DashboardQueries queries, DashboardMapper dashboardMapper) {
    this(queries, dashboardMapper, Clock.system(ZONE));
  }

  /** Test-only: fixed clock for day-boundary coverage. */
  DashboardService(DashboardQueries queries, DashboardMapper dashboardMapper, Clock clock) {
    this.queries = queries;
    this.dashboardMapper = dashboardMapper;
    this.clock = clock;
  }

  public DashboardResponse getDashboard(String shopId) {
    try {
      Days d = new Days(LocalDate.now(clock.withZone(ZONE)));
      Instant daysFrom = d.weekStart.isBefore(d.monthStart) ? d.start(d.weekStart) : d.start(d.monthStart);

      SalesSummary sales = queries.sales(shopId, daysFrom, ZONE);
      StockSummary stock =
          queries.stock(
              shopId,
              d.start(d.today),
              d.start(d.today.plusDays(1)),
              d.start(d.weekStart),
              d.start(d.monthStart),
              LOW_STOCK_THRESHOLD,
              LOW_STOCK_LIST_SIZE);

      return dashboardMapper.toDashboardResponse(
          keyMetrics(sales, stock, d),
          lowStockItems(stock),
          revenueBreakdown(sales, d),
          productInsights(stock),
          salesTrend(sales, d));
    } catch (DataAccessException | com.mongodb.MongoException e) {
      log.error("Database error while fetching dashboard data for shop: {}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error fetching dashboard data", e);
    } catch (BaseException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Unexpected error while fetching dashboard data for shop: {}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred", e);
    }
  }

  /** The calendar days the dashboard talks about, in {@link #ZONE}. */
  private static final class Days {
    final LocalDate today;
    final LocalDate yesterday;
    final LocalDate weekStart; // last 7 days including today
    final LocalDate monthStart;

    Days(LocalDate today) {
      this.today = today;
      this.yesterday = today.minusDays(1);
      this.weekStart = today.minusDays(6);
      this.monthStart = today.withDayOfMonth(1);
    }

    Instant start(LocalDate day) {
      return day.atStartOfDay(ZONE).toInstant();
    }
  }

  private DashboardResponse.KeyMetrics keyMetrics(SalesSummary sales, StockSummary stock, Days d) {
    DayTotal today = day(sales.days(), d.today);
    BigDecimal averageOrderValue =
        today.orders() > 0
            ? today.revenue().divide(BigDecimal.valueOf(today.orders()), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    DashboardResponse.KeyMetrics metrics = new DashboardResponse.KeyMetrics();
    metrics.setTotalProducts(stock.distinctLotIds());
    metrics.setTotalRevenueToday(today.revenue());
    metrics.setOrdersToday(today.orders());
    metrics.setLowStockItemsCount(stock.lowStock());
    metrics.setAverageOrderValue(averageOrderValue);
    metrics.setTotalCustomers(sales.customers());
    metrics.setTotalRevenueAllTime(sales.revenueAllTime());
    metrics.setTotalOrdersAllTime(sales.ordersAllTime());
    return metrics;
  }

  private List<DashboardResponse.LowStockItem> lowStockItems(StockSummary stock) {
    List<DashboardResponse.LowStockItem> items = new ArrayList<>();
    for (DashboardQueries.LowLot lot : stock.lowest()) {
      DashboardResponse.LowStockItem item = new DashboardResponse.LowStockItem();
      item.setInventoryId(lot.id());
      item.setName(lot.name());
      item.setCurrentCount(lot.baseCount());
      item.setThreshold(LOW_STOCK_THRESHOLD);
      item.setLotId(lot.lotId());
      item.setBarcode(lot.barcode());
      items.add(item);
    }
    return items;
  }

  private DashboardResponse.RevenueBreakdown revenueBreakdown(SalesSummary sales, Days d) {
    BigDecimal todayRevenue = day(sales.days(), d.today).revenue();
    BigDecimal yesterdayRevenue = day(sales.days(), d.yesterday).revenue();

    BigDecimal percentageChangeToday = BigDecimal.ZERO;
    if (yesterdayRevenue.compareTo(BigDecimal.ZERO) > 0) {
      percentageChangeToday =
          todayRevenue
              .subtract(yesterdayRevenue)
              .divide(yesterdayRevenue, 4, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
    } else if (todayRevenue.compareTo(BigDecimal.ZERO) > 0) {
      percentageChangeToday = BigDecimal.valueOf(100); // 100% increase from 0
    }

    DashboardResponse.RevenueBreakdown breakdown = new DashboardResponse.RevenueBreakdown();
    breakdown.setToday(todayRevenue);
    breakdown.setYesterday(yesterdayRevenue);
    breakdown.setThisWeek(revenueFrom(sales.days(), d.weekStart));
    breakdown.setThisMonth(revenueFrom(sales.days(), d.monthStart));
    breakdown.setPercentageChangeToday(percentageChangeToday);
    return breakdown;
  }

  private DashboardResponse.ProductInsights productInsights(StockSummary stock) {
    DashboardResponse.ProductInsights insights = new DashboardResponse.ProductInsights();
    insights.setTotalUniqueProducts(stock.distinctLotIds());
    insights.setProductsAddedToday(stock.addedToday());
    insights.setProductsAddedThisWeek(stock.addedThisWeek());
    insights.setProductsAddedThisMonth(stock.addedThisMonth());
    insights.setOutOfStockItems(stock.outOfStock());
    return insights;
  }

  private DashboardResponse.SalesTrend salesTrend(SalesSummary sales, Days d) {
    List<DashboardResponse.DailySales> last7Days = new ArrayList<>();
    BigDecimal bestDayRevenue = BigDecimal.ZERO;
    String bestDayDate = null;

    for (LocalDate date = d.weekStart; !date.isAfter(d.today); date = date.plusDays(1)) {
      DayTotal total = day(sales.days(), date);
      DashboardResponse.DailySales dailySales = new DashboardResponse.DailySales();
      dailySales.setDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
      dailySales.setRevenue(total.revenue());
      dailySales.setOrderCount(total.orders());
      last7Days.add(dailySales);
      if (total.revenue().compareTo(bestDayRevenue) > 0) {
        bestDayRevenue = total.revenue();
        bestDayDate = dailySales.getDate();
      }
    }

    DashboardResponse.SalesTrend trend = new DashboardResponse.SalesTrend();
    trend.setLast7Days(last7Days);
    trend.setBestDayRevenue(bestDayRevenue);
    trend.setBestDayDate(bestDayDate);
    return trend;
  }

  private static DayTotal day(Map<LocalDate, DayTotal> days, LocalDate date) {
    return days.getOrDefault(date, new DayTotal(BigDecimal.ZERO, 0));
  }

  /** Revenue of every day on or after {@code from} (later days included, as before). */
  private static BigDecimal revenueFrom(Map<LocalDate, DayTotal> days, LocalDate from) {
    BigDecimal sum = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, DayTotal> e : days.entrySet()) {
      if (!e.getKey().isBefore(from)) {
        sum = sum.add(e.getValue().revenue());
      }
    }
    return sum;
  }
}
