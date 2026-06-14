package com.inventory.product.service.vertical;

import com.inventory.pluginengine.InventoryExpiryBucketSummary;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.response.InventoryExpiryBucketsResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class InventoryVerticalExpiryHandler {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;
  private final InventoryRepository inventoryRepository;
  private final InventoryVerticalSearchHandler inventoryVerticalSearchHandler;

  public InventoryVerticalExpiryHandler(
      ShopRepository shopRepository,
      PluginRegistry pluginRegistry,
      InventoryRepository inventoryRepository,
      InventoryVerticalSearchHandler inventoryVerticalSearchHandler) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
    this.inventoryRepository = inventoryRepository;
    this.inventoryVerticalSearchHandler = inventoryVerticalSearchHandler;
  }

  public InventoryExpiryBucketsResponse getExpiryBuckets(String shopId, int expiringSoonDays) {
    InventorySearchProvider provider = resolveSearchProvider(shopId).orElse(null);
    if (provider == null) {
      return InventoryExpiryBucketsResponse.builder()
          .expiringSoonDays(expiringSoonDays > 0 ? expiringSoonDays : 30)
          .build();
    }
    InventoryExpiryBucketSummary summary =
        provider.aggregateExpiryBuckets(
            shopId, expiringSoonDays > 0 ? expiringSoonDays : 30);
    if (summary == null) {
      return InventoryExpiryBucketsResponse.builder()
          .expiringSoonDays(expiringSoonDays > 0 ? expiringSoonDays : 30)
          .build();
    }
    return InventoryExpiryBucketsResponse.builder()
        .expired(summary.getExpired())
        .expiringWithin7Days(summary.getExpiringWithin7Days())
        .expiringWithinSoonDays(summary.getExpiringWithinSoonDays())
        .expiringSoonTotal(
            summary.getExpiringWithin7Days() + summary.getExpiringWithinSoonDays())
        .totalWithExpiry(summary.getTotalWithExpiry())
        .expiringSoonDays(summary.getExpiringSoonDays())
        .build();
  }

  public List<Inventory> findNearExpiry(String shopId, int days, int limit) {
    int effectiveDays = days > 0 ? days : 30;
    return inventoryVerticalSearchHandler.search(
        shopId,
        null,
        Map.of("nearExpiryDays", String.valueOf(effectiveDays)),
        "expiryDate:asc",
        limit > 0 ? limit : 50);
  }

  public List<Inventory> findExpired(String shopId, int limit) {
    return inventoryVerticalSearchHandler.search(
        shopId,
        null,
        Map.of("expiryBefore", java.time.Instant.now().toString()),
        "expiryDate:asc",
        limit > 0 ? limit : 50);
  }

  public List<Inventory> findFefo(String shopId, String batchNo, int limit) {
    InventorySearchProvider provider = resolveSearchProvider(shopId).orElse(null);
    if (provider == null) {
      return List.of();
    }
    InventorySearchResult result = provider.searchFefo(shopId, batchNo, limit > 0 ? limit : 50);
    return hydrateInStockOrdered(shopId, result.getInventoryIds());
  }

  public List<String> findNearExpiryInventoryIds(String shopId, int days, int limit) {
    return findNearExpiry(shopId, days, limit).stream().map(Inventory::getId).toList();
  }

  public List<String> findExpiredInventoryIds(String shopId, int limit) {
    return findExpired(shopId, limit).stream().map(Inventory::getId).toList();
  }

  private List<Inventory> hydrateInStockOrdered(String shopId, List<String> inventoryIds) {
    if (inventoryIds == null || inventoryIds.isEmpty()) {
      return List.of();
    }
    List<Inventory> loaded =
        inventoryRepository.findByIdIn(inventoryIds).stream()
            .filter(inv -> shopId.equals(inv.getShopId()))
            .filter(this::hasPositiveStock)
            .toList();
    Map<String, Inventory> byId = new LinkedHashMap<>();
    for (Inventory inv : loaded) {
      byId.put(inv.getId(), inv);
    }
    List<Inventory> ordered = new ArrayList<>();
    for (String id : inventoryIds) {
      Inventory inv = byId.get(id);
      if (inv != null) {
        ordered.add(inv);
      }
    }
    return ordered;
  }

  private boolean hasPositiveStock(Inventory inventory) {
    if (inventory.getCurrentCount() != null
        && inventory.getCurrentCount().compareTo(BigDecimal.ZERO) > 0) {
      return true;
    }
    return inventory.getCurrentBaseCount() != null && inventory.getCurrentBaseCount() > 0;
  }

  private java.util.Optional<InventorySearchProvider> resolveSearchProvider(String shopId) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return java.util.Optional.empty();
    }
    return pluginRegistry.find(shop.getVerticalId()).flatMap(p -> p.getSearchProvider());
  }
}
