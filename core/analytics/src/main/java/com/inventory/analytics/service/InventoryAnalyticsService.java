package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.InventoryAnalyticsMapper;
import com.inventory.analytics.mapper.InventoryAnalyticsResponseParams;
import com.inventory.analytics.utils.InventoryAnalyticsScan;
import com.inventory.analytics.utils.InventoryAnalyticsScan.ListKind;
import com.inventory.analytics.utils.InventoryAnalyticsUtils;
import com.inventory.analytics.utils.ShopLotBatches;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsDto;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsItemsResponse;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsResponse;
import com.inventory.analytics.rest.dto.response.InventorySummaryDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.response.InventoryExpiryBucketsResponse;
import com.inventory.product.service.vertical.InventoryVerticalExpiryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class InventoryAnalyticsService {

  /** Rows of each list sent with the tab, and the default page size for more. */
  public static final int DEFAULT_LIST_LIMIT = 50;
  /** Largest page a caller may ask for. */
  public static final int MAX_LIST_LIMIT = 200;

  @Autowired
  private InventoryAnalyticsUtils analyticsUtils;

  @Autowired
  private InventoryAnalyticsMapper inventoryAnalyticsMapper;

  @Autowired
  private ShopLotBatches shopLots;

  @Autowired
  private MetricsWrapper metrics;

  @Autowired
  private InventoryVerticalExpiryHandler inventoryVerticalExpiryHandler;

  /**
   * Get comprehensive inventory analytics.
   *
   * <p>The summary covers every lot. Low stock, not selling, dead stock (and all items, when asked
   * for) carry their first {@code listLimit} rows, in the same order as before, with each list's
   * full size in {@code listTotals}; {@link #getInventoryAnalyticsItems} serves further pages. The
   * lots are read and worked through a batch at a time, so memory does not grow with the shop.
   *
   * @param shopId the shop ID
   * @param lowStockThreshold threshold for low stock alert (optional, defaults to 20% of received)
   * @param deadStockDays days without sales to consider dead stock (optional, defaults to 90)
   * @param expiringSoonDays days until expiry to alert (optional, defaults to 30)
   * @param includeAll whether to include all items in response (optional, defaults to false)
   * @param listLimit rows per list (optional, defaults to 50, at most 200)
   * @return inventory analytics response
   */
  public InventoryAnalyticsResponse getInventoryAnalytics(
      String shopId,
      Integer lowStockThreshold,
      Integer deadStockDays,
      Integer expiringSoonDays,
      Boolean includeAll,
      Integer listLimit) {

    requireShop(shopId);
    record("inventory");

    try {
      // Set defaults
      if (includeAll == null) {
        includeAll = false;
      }
      int effectiveExpiringSoonDays =
          expiringSoonDays != null && expiringSoonDays > 0 ? expiringSoonDays : 30;
      int limit = pageSize(listLimit);

      log.debug("Getting inventory analytics for shop: {}", shopId);

      Map<ListKind, Integer> keep = new EnumMap<>(ListKind.class);
      keep.put(ListKind.LOW_STOCK, limit);
      keep.put(ListKind.NOT_SELLING, limit);
      keep.put(ListKind.DEAD_STOCK, limit);
      if (includeAll) {
        keep.put(ListKind.ALL, limit);
      }
      Map<String, Instant> lastSold = analyticsUtils.getLastSoldDates(shopId);
      Instant now = Instant.now();
      InventoryAnalyticsScan scan =
          scan(shopId, keep, lastSold, now, lowStockThreshold, deadStockDays, effectiveExpiringSoonDays);

      // Summary from every lot, then expiry counts from the extension index
      InventorySummaryDto summary = scan.summary();
      InventoryExpiryBucketsResponse expiryBuckets =
          inventoryVerticalExpiryHandler.getExpiryBuckets(shopId, effectiveExpiringSoonDays);
      summary.setExpiredProducts(expiryBuckets.getExpired());
      summary.setExpiringSoonProducts(expiryBuckets.getExpiringSoonTotal());

      // Near-expiry and expired lists via indexed extension search (at most 200 each)
      List<Inventory> nearExpiryInventories =
          inventoryVerticalExpiryHandler.findNearExpiry(shopId, effectiveExpiringSoonDays, 200);
      List<Inventory> expiredInventories =
          inventoryVerticalExpiryHandler.findExpired(shopId, 200);

      List<InventoryAnalyticsDto> expiringSoonItems =
          analyticsUtils
              .calculateInventoryAnalytics(
                  shopId,
                  nearExpiryInventories,
                  lastSold,
                  now,
                  lowStockThreshold,
                  deadStockDays,
                  effectiveExpiringSoonDays)
              .stream()
              .filter(dto -> dto.getIsExpiringSoon() != null && dto.getIsExpiringSoon())
              .sorted(
                  (a, b) -> {
                    if (a.getDaysUntilExpiry() == null) return 1;
                    if (b.getDaysUntilExpiry() == null) return -1;
                    return a.getDaysUntilExpiry().compareTo(b.getDaysUntilExpiry());
                  })
              .collect(Collectors.toList());

      List<InventoryAnalyticsDto> expiredItems =
          analyticsUtils
              .calculateInventoryAnalytics(
                  shopId,
                  expiredInventories,
                  lastSold,
                  now,
                  lowStockThreshold,
                  deadStockDays,
                  effectiveExpiringSoonDays)
              .stream()
              .filter(dto -> dto.getIsExpired() != null && dto.getIsExpired())
              .sorted(
                  (a, b) -> {
                    if (a.getDaysUntilExpiry() == null) return 1;
                    if (b.getDaysUntilExpiry() == null) return -1;
                    return a.getDaysUntilExpiry().compareTo(b.getDaysUntilExpiry());
                  })
              .collect(Collectors.toList());

      Map<String, Integer> listTotals = new LinkedHashMap<>();
      for (ListKind kind : keep.keySet()) {
        listTotals.put(kind.field, scan.total(kind));
      }

      InventoryAnalyticsResponseParams params = InventoryAnalyticsResponseParams.builder()
          .summary(summary)
          .lowStockItems(scan.page(ListKind.LOW_STOCK, 0, limit))
          .notSellingItems(scan.page(ListKind.NOT_SELLING, 0, limit))
          .expiringSoonItems(expiringSoonItems)
          .expiredItems(expiredItems)
          .deadStockItems(scan.page(ListKind.DEAD_STOCK, 0, limit))
          .allItems(includeAll ? scan.page(ListKind.ALL, 0, limit) : null)
          .listTotals(listTotals)
          .listLimit(limit)
          .lowStockThreshold(lowStockThreshold)
          .deadStockDays(deadStockDays)
          .expiringSoonDays(expiringSoonDays)
          .includeAll(includeAll)
          .totalItems(scan.count())
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

  /**
   * One page of one list ({@code lowStockItems}, {@code notSellingItems}, {@code deadStockItems}
   * or {@code allItems}), in the same order and with the same figures as the tab.
   */
  public InventoryAnalyticsItemsResponse getInventoryAnalyticsItems(
      String shopId,
      String list,
      Integer page,
      Integer size,
      Integer lowStockThreshold,
      Integer deadStockDays,
      Integer expiringSoonDays) {

    requireShop(shopId);
    ListKind kind = ListKind.byField(list);
    if (kind == null) {
      throw new ValidationException(
          "list must be one of lowStockItems, notSellingItems, deadStockItems, allItems");
    }
    int p = page == null ? 0 : page;
    if (p < 0) {
      throw new ValidationException("page must be 0 or more");
    }
    int s = pageSize(size);
    record("inventory_items");

    try {
      int effectiveExpiringSoonDays =
          expiringSoonDays != null && expiringSoonDays > 0 ? expiringSoonDays : 30;
      long keep = (long) (p + 1) * s;
      if (keep > Integer.MAX_VALUE) {
        throw new ValidationException("page is too large");
      }
      InventoryAnalyticsScan scan = scan(
          shopId, Map.of(kind, (int) keep), analyticsUtils.getLastSoldDates(shopId), Instant.now(),
          lowStockThreshold, deadStockDays, effectiveExpiringSoonDays);
      return new InventoryAnalyticsItemsResponse(kind.field, scan.page(kind, p, s), p, s, scan.total(kind));
    } catch (ValidationException e) {
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting inventory analytics items: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving inventory analytics");
    } catch (Exception e) {
      log.error("Unexpected error while getting inventory analytics items: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve inventory analytics");
    }
  }

  /** Every lot of the shop through the per-lot figures, a batch at a time. */
  private InventoryAnalyticsScan scan(
      String shopId,
      Map<ListKind, Integer> keep,
      Map<String, Instant> lastSold,
      Instant now,
      Integer lowStockThreshold,
      Integer deadStockDays,
      int expiringSoonDays) {
    InventoryAnalyticsScan scan = new InventoryAnalyticsScan(keep);
    shopLots.forEach(shopId, batch -> analyticsUtils
        .calculateInventoryAnalytics(
            shopId, batch, lastSold, now, lowStockThreshold, deadStockDays, expiringSoonDays)
        .forEach(scan::add));
    return scan;
  }

  private static int pageSize(Integer requested) {
    if (requested == null) {
      return DEFAULT_LIST_LIMIT;
    }
    if (requested < 1 || requested > MAX_LIST_LIMIT) {
      throw new ValidationException("Rows per list must be between 1 and " + MAX_LIST_LIMIT);
    }
    return requested;
  }

  private static void requireShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }
  }

  private void record(String operation) {
    metrics.record(
        AnalyticsMetricsConstants.QUERIES_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        operation);
  }
}
