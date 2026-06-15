package com.inventory.product.service.vertical;

import com.inventory.pluginengine.InventoryExpiryBucketSummary;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.response.InventoryExpiryBucketsResponse;
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
  private final InventoryVerticalSearchHandler inventoryVerticalSearchHandler;

  public InventoryVerticalExpiryHandler(
      ShopRepository shopRepository,
      PluginRegistry pluginRegistry,
      InventoryVerticalSearchHandler inventoryVerticalSearchHandler) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
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
        .expiringSoonDays(summary.getExpiringSoonDays())
        .build();
  }

  public List<Inventory> findNearExpiry(String shopId, int days, int limit) {
    int effectiveDays = days > 0 ? days : 30;
    return inventoryVerticalSearchHandler
        .searchPage(
            shopId,
            null,
            Map.of("nearExpiryDays", String.valueOf(effectiveDays)),
            "expiryDate:asc",
            limit > 0 ? limit : 50,
            null,
            0)
        .items();
  }

  public List<Inventory> findExpired(String shopId, int limit) {
    return inventoryVerticalSearchHandler
        .searchPage(
            shopId,
            null,
            Map.of("expiryBefore", java.time.Instant.now().toString()),
            "expiryDate:asc",
            limit > 0 ? limit : 50,
            null,
            0)
        .items();
  }

  private java.util.Optional<InventorySearchProvider> resolveSearchProvider(String shopId) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return java.util.Optional.empty();
    }
    return pluginRegistry.find(shop.getVerticalId()).flatMap(p -> p.getSearchProvider());
  }
}
