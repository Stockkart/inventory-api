package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.InventoryAnalyticsMapper;
import com.inventory.analytics.mapper.InventoryAnalyticsResponseParams;
import com.inventory.analytics.utils.InventoryAnalyticsUtils;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsDto;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsResponse;
import com.inventory.analytics.rest.dto.response.InventorySummaryDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class InventoryAnalyticsService {

  @Autowired
  private InventoryAnalyticsUtils analyticsUtils;

  @Autowired
  private InventoryAnalyticsMapper inventoryAnalyticsMapper;

  @Autowired
  private InventoryRepository inventoryRepository;

  /**
   * Get comprehensive inventory analytics.
   *
   * @param shopId the shop ID
   * @param lowStockThreshold threshold for low stock alert (optional, defaults to 20% of received)
   * @param deadStockDays days without sales to consider dead stock (optional, defaults to 90)
   * @param expiringSoonDays days until expiry to alert (optional, defaults to 30)
   * @param includeAll whether to include all items in response (optional, defaults to false)
   * @return inventory analytics response
   */
  public InventoryAnalyticsResponse getInventoryAnalytics(
      String shopId,
      Integer lowStockThreshold,
      Integer deadStockDays,
      Integer expiringSoonDays,
      Boolean includeAll) {

    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    try {
      // Set defaults
      if (includeAll == null) {
        includeAll = false;
      }

      log.debug("Getting inventory analytics for shop: {}", shopId);

      // Get all inventory for the shop
      List<Inventory> allInventories = inventoryRepository.findByShopId(shopId);

      // Calculate analytics for all items
      List<InventoryAnalyticsDto> allAnalytics = analyticsUtils.calculateInventoryAnalytics(
          shopId, allInventories, lowStockThreshold, deadStockDays, expiringSoonDays);

      // Calculate summary
      InventorySummaryDto summary = analyticsUtils.calculateSummary(allAnalytics);

      // Filter items by category
      List<InventoryAnalyticsDto> lowStockItems = allAnalytics.stream()
          .filter(InventoryAnalyticsDto::getIsLowStock)
          .sorted((a, b) -> a.getCurrentCount().compareTo(b.getCurrentCount())) // Sort by stock level ascending
          .collect(Collectors.toList());

      List<InventoryAnalyticsDto> notSellingItems = allAnalytics.stream()
          .filter(dto -> (dto.getSoldCount() == null || dto.getSoldCount() == 0) && dto.getCurrentCount() > 0)
          .sorted((a, b) -> {
            // Sort by days since received (oldest first)
            if (a.getDaysSinceReceived() == null) return 1;
            if (b.getDaysSinceReceived() == null) return -1;
            return b.getDaysSinceReceived().compareTo(a.getDaysSinceReceived());
          })
          .collect(Collectors.toList());

      List<InventoryAnalyticsDto> expiringSoonItems = allAnalytics.stream()
          .filter(dto -> dto.getIsExpiringSoon() != null && dto.getIsExpiringSoon())
          .sorted((a, b) -> {
            // Sort by days until expiry (soonest first)
            if (a.getDaysUntilExpiry() == null) return 1;
            if (b.getDaysUntilExpiry() == null) return -1;
            return a.getDaysUntilExpiry().compareTo(b.getDaysUntilExpiry());
          })
          .collect(Collectors.toList());

      List<InventoryAnalyticsDto> expiredItems = allAnalytics.stream()
          .filter(dto -> dto.getIsExpired() != null && dto.getIsExpired())
          .sorted((a, b) -> {
            // Sort by days until expiry (most expired first)
            if (a.getDaysUntilExpiry() == null) return 1;
            if (b.getDaysUntilExpiry() == null) return -1;
            return a.getDaysUntilExpiry().compareTo(b.getDaysUntilExpiry());
          })
          .collect(Collectors.toList());

      List<InventoryAnalyticsDto> deadStockItems = allAnalytics.stream()
          .filter(InventoryAnalyticsDto::getIsDeadStock)
          .sorted((a, b) -> {
            Long aDays = a.getLastSoldDate() != null ? a.getDaysSinceReceived() : a.getDaysSinceReceived();
            Long bDays = b.getLastSoldDate() != null ? b.getDaysSinceReceived() : b.getDaysSinceReceived();
            if (aDays == null) return 1;
            if (bDays == null) return -1;
            return bDays.compareTo(aDays);
          })
          .collect(Collectors.toList());

      InventoryAnalyticsResponseParams params = InventoryAnalyticsResponseParams.builder()
          .summary(summary)
          .lowStockItems(lowStockItems)
          .notSellingItems(notSellingItems)
          .expiringSoonItems(expiringSoonItems)
          .expiredItems(expiredItems)
          .deadStockItems(deadStockItems)
          .allItems(includeAll != null && includeAll ? allAnalytics : null)
          .lowStockThreshold(lowStockThreshold)
          .deadStockDays(deadStockDays)
          .expiringSoonDays(expiringSoonDays)
          .includeAll(includeAll)
          .totalItems(allInventories.size())
          .build();

      return inventoryAnalyticsMapper.toResponse(params);

    } catch (ValidationException e) {
      log.warn("Validation error in inventory analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting inventory analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving inventory analytics");
    } catch (Exception e) {
      log.error("Unexpected error while getting inventory analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve inventory analytics");
    }
  }
}

