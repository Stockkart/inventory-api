package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.VendorAnalyticsMapper;
import com.inventory.analytics.mapper.VendorAnalyticsResponseParams;
import com.inventory.analytics.utils.ShopLotBatches;
import com.inventory.analytics.utils.VendorAnalyticsUtils;
import com.inventory.analytics.utils.VendorLotTotals;
import com.inventory.analytics.utils.AnalyticsSalesQueries;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.analytics.rest.dto.response.VendorAnalyticsResponse;
import com.inventory.analytics.rest.dto.response.VendorDependencyDto;
import com.inventory.analytics.rest.dto.response.VendorRevenueDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.user.domain.model.Vendor;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@Transactional(readOnly = true)
public class VendorAnalyticsService {

  @Autowired
  private VendorAnalyticsUtils vendorUtils;

  @Autowired
  private AnalyticsSalesQueries salesQueries;

  @Autowired
  private VendorAnalyticsMapper vendorAnalyticsMapper;

  @Autowired
  private ShopLotBatches shopLots;

  @Autowired
  private InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;

  @Autowired
  private MetricsWrapper metrics;

  /**
   * Get comprehensive vendor analytics.
   *
   * @param shopId the shop ID
   * @param startDate start date for revenue calculations (optional, defaults to 30 days ago)
   * @param endDate end date for revenue calculations (optional, defaults to now)
   * @return vendor analytics response
   */
  public VendorAnalyticsResponse getVendorAnalytics(
      String shopId,
      Instant startDate,
      Instant endDate) {

    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    metrics.record(
        AnalyticsMetricsConstants.QUERIES_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        "vendor");

    try {
      // Set default values
      if (endDate == null) {
        endDate = Instant.now();
      }
      if (startDate == null) {
        startDate = endDate.minus(30, ChronoUnit.DAYS);
      }

      log.debug("Getting vendor analytics for shop: {} from {} to {}", shopId, startDate, endDate);

      // Period's lines grouped by MongoDB (with the bills each group is on, for bill counts)
      List<LineGroup> lines = salesQueries.lineGroups(shopId, startDate, endDate, null, true);
      long bills = salesQueries.billTotals(shopId, startDate, endDate).bills();
      Set<String> soldLotIds = new LinkedHashSet<>();
      for (LineGroup g : lines) {
        if (StringUtils.hasText(g.lotId())) {
          soldLotIds.add(g.lotId());
        }
      }

      // The shop's lots, a batch at a time: per-vendor stock totals, and the lots sold from kept
      VendorLotTotals totals = new VendorLotTotals(soldLotIds, Instant.now());
      shopLots.forEach(shopId, batch -> {
        Map<String, Map<String, Object>> extensionByInventoryId =
            inventoryVerticalExtensionHandler.loadExtensionFieldsBatch(
                shopId, batch.stream().map(Inventory::getId).toList());
        for (Inventory inv : batch) {
          totals.add(inv, extensionByInventoryId.getOrDefault(inv.getId(), Map.of()));
        }
      });

      // Lots by id: sold lots of this shop are kept; read only sold lots not among them
      Map<String, Inventory> lotsById = new HashMap<>(totals.soldLots());
      Set<String> missing = new LinkedHashSet<>();
      for (String lotId : soldLotIds) {
        if (!lotsById.containsKey(lotId)) {
          missing.add(lotId);
        }
      }
      lotsById.putAll(salesQueries.lots(missing));

      // Vendors, read once for all sections
      Set<String> vendorIds = new HashSet<>(totals.vendorIds());
      for (LineGroup g : lines) {
        Inventory inv = g.lotId() == null ? null : lotsById.get(g.lotId());
        if (inv != null && inv.getVendorId() != null) {
          vendorIds.add(inv.getVendorId());
        }
      }
      Map<String, Vendor> vendors = vendorUtils.vendorsById(vendorIds);

      // Stock analytics
      var vendorStockAnalytics = vendorUtils.calculateVendorStockAnalytics(totals, lines, lotsById, vendors);

      // Revenue analytics
      List<VendorRevenueDto> vendorRevenues = vendorUtils.calculateVendorRevenueAnalytics(lines, lotsById, vendors);

      // Performance analytics
      var vendorPerformanceAnalytics = vendorUtils.calculateVendorPerformanceAnalytics(totals, vendors);

      // Category expiry analytics
      var categoryExpiryAnalytics = vendorUtils.calculateCategoryExpiryAnalytics(totals, vendors);

      // Dependency analytics
      List<VendorDependencyDto> dependencies = vendorUtils.calculateVendorDependencyAnalytics(vendorRevenues, totals);

      // Calculate overall summary (stock value = cost x current over all lots)
      BigDecimal totalInventoryValue = totals.totalValue();

      BigDecimal totalRevenue = vendorRevenues.stream()
          .map(VendorRevenueDto::getTotalRevenue)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalExpiredStockValue = totals.expiredValue();

      BigDecimal totalUnsoldStockValue = totals.totalValue();

      // Calculate top vendor percentages
      BigDecimal topVendorRevenuePercent = BigDecimal.ZERO;
      BigDecimal top3VendorRevenuePercent = BigDecimal.ZERO;
      String mostDependentVendorId = null;
      String mostDependentVendorName = null;

      if (!vendorRevenues.isEmpty() && totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
        topVendorRevenuePercent = vendorRevenues.get(0).getTotalRevenue()
            .divide(totalRevenue, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));

        BigDecimal top3Revenue = vendorRevenues.stream()
            .limit(3)
            .map(VendorRevenueDto::getTotalRevenue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        top3VendorRevenuePercent = top3Revenue
            .divide(totalRevenue, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
      }

      if (!dependencies.isEmpty()) {
        VendorDependencyDto mostDependent = dependencies.get(0);
        mostDependentVendorId = mostDependent.getVendorId();
        mostDependentVendorName = mostDependent.getVendorName();
      }

      VendorAnalyticsResponseParams params = VendorAnalyticsResponseParams.builder()
          .vendorStockAnalytics(vendorStockAnalytics)
          .vendorRevenueAnalytics(vendorRevenues)
          .vendorPerformanceAnalytics(vendorPerformanceAnalytics)
          .categoryExpiryAnalytics(categoryExpiryAnalytics)
          .vendorDependencyAnalytics(dependencies)
          .totalVendors(vendorRevenues.size())
          .totalInventoryValue(totalInventoryValue)
          .totalRevenue(totalRevenue)
          .totalExpiredStockValue(totalExpiredStockValue)
          .totalUnsoldStockValue(totalUnsoldStockValue)
          .topVendorRevenuePercentage(topVendorRevenuePercent)
          .top3VendorRevenuePercentage(top3VendorRevenuePercent)
          .mostDependentVendorId(mostDependentVendorId)
          .mostDependentVendorName(mostDependentVendorName)
          .startDate(startDate)
          .endDate(endDate)
          .totalInventories(totals.lots())
          .totalPurchases(Math.toIntExact(bills))
          .build();

      return vendorAnalyticsMapper.toResponse(params);

    } catch (ValidationException e) {
      log.warn("Validation error in vendor analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting vendor analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving vendor analytics", e);
    } catch (Exception e) {
      log.error("Unexpected error while getting vendor analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve vendor analytics", e);
    }
  }
}
