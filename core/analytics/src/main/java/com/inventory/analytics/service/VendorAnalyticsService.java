package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.helper.VendorAnalyticsHelper;
import com.inventory.analytics.helper.SalesAnalyticsHelper;
import com.inventory.analytics.rest.dto.vendor.VendorAnalyticsResponse;
import com.inventory.analytics.rest.dto.vendor.VendorDependencyDto;
import com.inventory.analytics.rest.dto.vendor.VendorRevenueDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.repository.InventoryRepository;
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
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional(readOnly = true)
public class VendorAnalyticsService {

  @Autowired
  private VendorAnalyticsHelper vendorHelper;

  @Autowired
  private SalesAnalyticsHelper salesHelper;

  @Autowired
  private InventoryRepository inventoryRepository;

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

      // Get completed purchases for revenue calculations
      List<Purchase> purchases = salesHelper.getCompletedPurchases(shopId, startDate, endDate);

      // Build response
      VendorAnalyticsResponse response = new VendorAnalyticsResponse();

      // Stock analytics
      response.setVendorStockAnalytics(vendorHelper.calculateVendorStockAnalytics(shopId, allInventories, purchases));

      // Revenue analytics
      List<VendorRevenueDto> vendorRevenues = vendorHelper.calculateVendorRevenueAnalytics(shopId, purchases);
      response.setVendorRevenueAnalytics(vendorRevenues);

      // Performance analytics
      response.setVendorPerformanceAnalytics(vendorHelper.calculateVendorPerformanceAnalytics(shopId, allInventories));

      // Category expiry analytics
      response.setCategoryExpiryAnalytics(vendorHelper.calculateCategoryExpiryAnalytics(shopId, allInventories));

      // Dependency analytics
      List<VendorDependencyDto> dependencies = vendorHelper.calculateVendorDependencyAnalytics(shopId, vendorRevenues, allInventories);
      response.setVendorDependencyAnalytics(dependencies);

      // Calculate overall summary
      BigDecimal totalInventoryValue = allInventories.stream()
          .map(inv -> {
            Integer current = getCurrentBaseCount(inv);
            BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
            return costPrice.multiply(BigDecimal.valueOf(current));
          })
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalRevenue = vendorRevenues.stream()
          .map(VendorRevenueDto::getTotalRevenue)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalExpiredStockValue = allInventories.stream()
          .filter(inv -> inv.getExpiryDate() != null && inv.getExpiryDate().isBefore(Instant.now()))
          .map(inv -> {
            Integer current = getCurrentBaseCount(inv);
            BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
            return costPrice.multiply(BigDecimal.valueOf(current));
          })
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalUnsoldStockValue = allInventories.stream()
          .map(inv -> {
            Integer current = getCurrentBaseCount(inv);
            BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
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

      response.setTotalVendors(vendorRevenues.size());
      response.setTotalInventoryValue(totalInventoryValue);
      response.setTotalRevenue(totalRevenue);
      response.setTotalExpiredStockValue(totalExpiredStockValue);
      response.setTotalUnsoldStockValue(totalUnsoldStockValue);
      response.setTopVendorRevenuePercentage(topVendorRevenuePercent);
      response.setTop3VendorRevenuePercentage(top3VendorRevenuePercent);
      response.setMostDependentVendorId(mostDependentVendorId);
      response.setMostDependentVendorName(mostDependentVendorName);

      // Metadata
      Map<String, Object> meta = new HashMap<>();
      meta.put("startDate", startDate);
      meta.put("endDate", endDate);
      meta.put("totalInventories", allInventories.size());
      meta.put("totalPurchases", purchases.size());
      response.setMeta(meta);

      return response;

    } catch (ValidationException e) {
      log.warn("Validation error in vendor analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting vendor analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving vendor analytics");
    } catch (Exception e) {
      log.error("Unexpected error while getting vendor analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve vendor analytics");
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

