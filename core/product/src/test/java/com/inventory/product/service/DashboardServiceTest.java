package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.BaseException;
import com.inventory.product.mapper.DashboardMapper;
import com.inventory.product.rest.dto.response.DashboardResponse;
import com.inventory.product.service.DashboardQueries.DayTotal;
import com.inventory.product.service.DashboardQueries.LowLot;
import com.inventory.product.service.DashboardQueries.SalesSummary;
import com.inventory.product.service.DashboardQueries.StockSummary;
import com.mongodb.MongoTimeoutException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Turning the DB totals into the response; day boundaries are India days. */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

  private static final String SHOP = "shop1";
  /** 01:30 IST on 2 March 2026 — still 1 March in UTC. */
  private static final Instant NOW = Instant.parse("2026-03-01T20:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

  @Mock private DashboardQueries queries;
  private DashboardService service;

  @BeforeEach
  void setUp() {
    service = new DashboardService(queries, new DashboardMapper() {}, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Instant ist(LocalDate day) {
    return day.atStartOfDay(DashboardService.ZONE).toInstant();
  }

  private static StockSummary noStock() {
    return new StockSummary(0, 0, 0, 0, 0, 0, List.of());
  }

  @Test
  void todayIsTheIndiaDayAndTheQueriesGetIndiaBoundaries() {
    when(queries.sales(eq(SHOP), any(), eq(DashboardService.ZONE)))
        .thenReturn(new SalesSummary(BigDecimal.ZERO, 0, 0, Map.of()));
    when(queries.stock(eq(SHOP), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(noStock());

    DashboardResponse r = service.getDashboard(SHOP);

    // last 7 days starts on 24 Feb (before 1 March), so per-day totals are needed from then
    verify(queries).sales(SHOP, ist(LocalDate.of(2026, 2, 24)), DashboardService.ZONE);
    verify(queries)
        .stock(
            SHOP,
            ist(TODAY),
            ist(TODAY.plusDays(1)),
            ist(LocalDate.of(2026, 2, 24)),
            ist(LocalDate.of(2026, 3, 1)),
            10,
            20);
    List<DashboardResponse.DailySales> days = r.getSalesTrend().getLast7Days();
    assertEquals(7, days.size());
    assertEquals("2026-02-24", days.get(0).getDate());
    assertEquals("2026-03-02", days.get(6).getDate());
    assertNull(r.getSalesTrend().getBestDayDate());
    assertEquals(BigDecimal.ZERO, r.getKeyMetrics().getAverageOrderValue());
    assertEquals(BigDecimal.ZERO, r.getRevenueBreakdown().getPercentageChangeToday());
  }

  @Test
  void monthStartingBeforeTheWeekWidensTheDayRange() {
    service =
        new DashboardService(
            queries, new DashboardMapper() {}, Clock.fixed(Instant.parse("2026-03-20T06:00:00Z"), ZoneOffset.UTC));
    when(queries.sales(eq(SHOP), any(), any())).thenReturn(new SalesSummary(BigDecimal.ZERO, 0, 0, Map.of()));
    when(queries.stock(eq(SHOP), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(noStock());

    service.getDashboard(SHOP);

    verify(queries).sales(SHOP, ist(LocalDate.of(2026, 3, 1)), DashboardService.ZONE);
  }

  @Test
  void breakdownTrendAndKeyMetricsComeFromTheDayTotals() {
    Map<LocalDate, DayTotal> days =
        Map.of(
            LocalDate.of(2026, 2, 24), new DayTotal(new BigDecimal("50.00"), 1), // in week, not in month
            LocalDate.of(2026, 3, 1), new DayTotal(new BigDecimal("200.00"), 4), // yesterday
            TODAY, new DayTotal(new BigDecimal("300.00"), 3),
            LocalDate.of(2026, 3, 5), new DayTotal(new BigDecimal("7.00"), 1)); // future-dated sale
    when(queries.sales(eq(SHOP), any(), any()))
        .thenReturn(new SalesSummary(new BigDecimal("9999.50"), 120, 33, days));
    when(queries.stock(eq(SHOP), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            new StockSummary(
                41, 6, 2, 1, 5, 9, List.of(new LowLot("lot-a", "Para 500", "L1", "890", 0), new LowLot("lot-b", "Crocin", "L2", null, 4))));

    DashboardResponse r = service.getDashboard(SHOP);

    DashboardResponse.KeyMetrics k = r.getKeyMetrics();
    assertEquals(41L, k.getTotalProducts());
    assertEquals(new BigDecimal("300.00"), k.getTotalRevenueToday());
    assertEquals(3L, k.getOrdersToday());
    assertEquals(new BigDecimal("100.00"), k.getAverageOrderValue());
    assertEquals(6L, k.getLowStockItemsCount());
    assertEquals(33L, k.getTotalCustomers());
    assertEquals(new BigDecimal("9999.50"), k.getTotalRevenueAllTime());
    assertEquals(120L, k.getTotalOrdersAllTime());

    DashboardResponse.RevenueBreakdown b = r.getRevenueBreakdown();
    assertEquals(new BigDecimal("300.00"), b.getToday());
    assertEquals(new BigDecimal("200.00"), b.getYesterday());
    assertEquals(new BigDecimal("557.00"), b.getThisWeek());
    assertEquals(new BigDecimal("507.00"), b.getThisMonth());
    assertEquals(new BigDecimal("50.00"), b.getPercentageChangeToday());

    DashboardResponse.SalesTrend t = r.getSalesTrend();
    assertEquals(new BigDecimal("300.00"), t.getBestDayRevenue());
    assertEquals("2026-03-02", t.getBestDayDate());
    assertEquals(4L, t.getLast7Days().get(5).getOrderCount());
    assertEquals(BigDecimal.ZERO, t.getLast7Days().get(1).getRevenue());

    DashboardResponse.ProductInsights p = r.getProductInsights();
    assertEquals(41L, p.getTotalUniqueProducts());
    assertEquals(1L, p.getProductsAddedToday());
    assertEquals(5L, p.getProductsAddedThisWeek());
    assertEquals(9L, p.getProductsAddedThisMonth());
    assertEquals(2L, p.getOutOfStockItems());

    assertEquals(2, r.getLowStockItems().size());
    DashboardResponse.LowStockItem first = r.getLowStockItems().get(0);
    assertEquals("lot-a", first.getInventoryId());
    assertEquals("Para 500", first.getName());
    assertEquals(0, first.getCurrentCount());
    assertEquals(10, first.getThreshold());
    assertEquals("L1", first.getLotId());
    assertEquals("890", first.getBarcode());
  }

  @Test
  void salesFromZeroYesterdayIsAHundredPercentUp() {
    when(queries.sales(eq(SHOP), any(), any()))
        .thenReturn(new SalesSummary(BigDecimal.TEN, 1, 0, Map.of(TODAY, new DayTotal(BigDecimal.TEN, 1))));
    when(queries.stock(eq(SHOP), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(noStock());

    assertEquals(BigDecimal.valueOf(100), service.getDashboard(SHOP).getRevenueBreakdown().getPercentageChangeToday());
  }

  @Test
  void dbTimeoutIsWrappedWithItsCauseSoTheBusyHandlerCanSeeIt() {
    MongoTimeoutException timeout = new MongoTimeoutException("no connection");
    when(queries.sales(eq(SHOP), any(), any())).thenThrow(timeout);

    BaseException e = assertThrows(BaseException.class, () -> service.getDashboard(SHOP));
    assertEquals(timeout, e.getCause());
  }
}
