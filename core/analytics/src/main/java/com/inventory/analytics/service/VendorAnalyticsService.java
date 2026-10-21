package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.VendorAnalyticsMapper;
import com.inventory.analytics.mapper.VendorAnalyticsResponseParams;
import com.inventory.analytics.utils.VendorAnalyticsUtils;
import com.inventory.analytics.utils.AnalyticsSalesQueries;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.analytics.rest.dto.response.VendorAnalyticsResponse;
import com.inventory.analytics.rest.dto.response.VendorDependencyDto;
import com.inventory.analytics.rest.dto.response.VendorRevenueDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.user.domain.model.Vendor;
import com.inventory.pluginengine.VerticalFieldsReader;
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
  private InventoryRepository inventoryRepository;

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

      // Get all inventory for the shop
      List<Inventory> allInventories = inventoryRepository.findByShopId(shopId);

      // Period's lines grouped by MongoDB (with the bills each group is on, for bill counts)
      List<LineGroup> lines = salesQueries.lineGroups(shopId, startDate, endDate, null, true);
      long bills = salesQueries.billTotals(shopId, startDate, endDate).bills();

      // Lots by id: the shop's lots are already loaded; read only sold lots not among them
      Map<String, Inventory> lotsById = new HashMap<>();
      for (Inventory inv : allInventories) {
        lotsById.put(inv.getId(), inv);
      }
      Set<String> missing = new LinkedHashSet<>();
      for (LineGroup g : lines) {
        if (StringUtils.hasText(g.lotId()) && !lotsById.containsKey(g.lotId())) {
          missing.add(g.lotId());
        }
      }
      lotsById.putAll(salesQueries.lots(missing));

      // Vendors and vertical extension fields, read once for all sections
      Set<String> vendorIds = new HashSet<>();
      for (Inventory inv : allInventories) {
        if (inv.getVendorId() != null) {
          vendorIds.add(inv.getVendorId());
        }
      }
      for (LineGroup g : lines) {
        Inventory inv = g.lotId() == null ? null : lotsById.get(g.lotId());
        if (inv != null && inv.getVendorId() != null) {
          vendorIds.add(inv.getVendorId());
        }
      }
      Map<String, Vendor> vendors = vendorUtils.vendorsById(vendorIds);
      Map<String, Map<String, Object>> extensionByInventoryId =
          inventoryVerticalExtensionHandler.loadExtensionFieldsBatch(
              shopId, allInventories.stream().map(Inventory::getId).toList());

      // Stock analytics
      var vendorStockAnalytics = vendorUtils.calculateVendorStockAnalytics(
          allInventories, extensionByInventoryId, lines, lotsById, vendors);

      // Revenue analytics
      List<VendorRevenueDto> vendorRevenues = vendorUtils.calculateVendorRevenueAnalytics(lines, lotsById, vendors);

      // Performance analytics
      var vendorPerformanceAnalytics = vendorUtils.calculateVendorPerformanceAnalytics(
          allInventories, extensionByInventoryId, vendors);

      // Category expiry analytics
      var categoryExpiryAnalytics = vendorUtils.calculateCategoryExpiryAnalytics(
          allInventories, extensionByInventoryId, vendors);

      // Dependency analytics
      List<VendorDependencyDto> dependencies = vendorUtils.calculateVendorDependencyAnalytics(shopId, vendorRevenues, allInventories);

      // Calculate overall summary
      BigDecimal totalInventoryValue = allInventories.stream()
          .map(inv -> {
            Integer current = getCurrentBaseCount(inv);
            BigDecimal costPrice = inv.costForValuation() != null ? inv.costForValuation() : BigDecimal.ZERO;
            return costPrice.multiply(BigDecimal.valueOf(current));
          })
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalRevenue = vendorRevenues.stream()
          .map(VendorRevenueDto::getTotalRevenue)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalExpiredStockValue = allInventories.stream()
          .filter(
              inv -> {
                Instant expiry =
                    VerticalFieldsReader.expiryDateFrom(
                        extensionByInventoryId.getOrDefault(inv.getId(), Map.of()));
                return expiry != null && expiry.isBefore(Instant.now());
              })
          .map(inv -> {
            Integer current = getCurrentBaseCount(inv);
            BigDecimal costPrice = inv.costForValuation() != null ? inv.costForValuation() : BigDecimal.ZERO;
            return costPrice.multiply(BigDecimal.valueOf(current));
          })
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalUnsoldStockValue = allInventories.stream()
          .map(inv -> {
            Integer current = getCurrentBaseCount(inv);
            BigDecimal costPrice = inv.costForValuation() != null ? inv.costForValuation() : BigDecimal.ZERO;
            return costPrice.multiply(BigDecimal.valueOf(current));
          })
          .reduce(BigDecimal.ZERO, BigDecimal::add);

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
          .totalInventories(allInventories.size())
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

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() != null) {
      return inventory.getCurrentCount()
          .multiply(BigDecimal.valueOf(getDisplayToBaseFactor(inventory)))
          .setScale(0, RoundingMode.HALF_UP)
          .intValue();
    }
    return 0;
  }

  private int getDisplayToBaseFactor(Inventory inventory) {
    if (inventory.getUnitConversions() == null
        || inventory.getUnitConversions().getFactor() == null
        || inventory.getUnitConversions().getFactor() <= 0) {
      return 1;
    }
    return inventory.getUnitConversions().getFactor();
  }
}

