package com.inventory.product.service.vertical;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class InventoryVerticalSearchHandler {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;
  private final SchemaLoader schemaLoader;
  private final InventoryRepository inventoryRepository;

  public InventoryVerticalSearchHandler(
      ShopRepository shopRepository,
      PluginRegistry pluginRegistry,
      SchemaLoader schemaLoader,
      InventoryRepository inventoryRepository) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
    this.schemaLoader = schemaLoader;
    this.inventoryRepository = inventoryRepository;
  }

  public List<Inventory> search(
      String shopId, String q, Map<String, String> filters, String sort, int limit) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return coreTextSearch(shopId, q);
    }

    validateFilters(shop, filters);

    List<String> extensionIds = null;
    boolean hasFilters = filters != null && !filters.isEmpty();
    if (hasFilters) {
      extensionIds =
          pluginRegistry
              .find(shop.getVerticalId())
              .flatMap(p -> p.getSearchProvider())
              .map(
                  provider ->
                      provider.search(
                          shopId,
                          InventorySearchQuery.builder()
                              .filters(filters)
                              .sort(sort)
                              .limit(limit > 0 ? limit : 50)
                              .build()))
              .map(InventorySearchResult::getInventoryIds)
              .orElse(List.of());
      if (extensionIds.isEmpty()) {
        return List.of();
      }
    }

    List<Inventory> results;
    if (StringUtils.hasText(q)) {
      results = inventoryRepository.searchByShopIdAndQuery(shopId, q.trim());
      if (extensionIds != null) {
        Set<String> allowed = new HashSet<>(extensionIds);
        results = results.stream().filter(inv -> allowed.contains(inv.getId())).toList();
      }
    } else if (extensionIds != null) {
      results =
          inventoryRepository.findByIdIn(extensionIds).stream()
              .filter(inv -> shopId.equals(inv.getShopId()))
              .toList();
      Map<String, Inventory> byId = new LinkedHashMap<>();
      for (Inventory inv : results) {
        byId.put(inv.getId(), inv);
      }
      results = new ArrayList<>();
      for (String id : extensionIds) {
        Inventory inv = byId.get(id);
        if (inv != null) {
          results.add(inv);
        }
      }
    } else {
      results = coreTextSearch(shopId, q);
    }

    if (limit > 0 && results.size() > limit) {
      return results.subList(0, limit);
    }
    return results;
  }

  private List<Inventory> coreTextSearch(String shopId, String q) {
    if (!StringUtils.hasText(q)) {
      return List.of();
    }
    return inventoryRepository.searchByShopIdAndQuery(shopId, q.trim());
  }

  private void validateFilters(Shop shop, Map<String, String> filters) {
    if (filters == null || filters.isEmpty()) {
      return;
    }
    VerticalSchema schema = schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
    Set<String> searchable = searchableKeys(schema);
    List<String> unsupported = new ArrayList<>();
    for (String key : filters.keySet()) {
      if (!searchable.contains(key)) {
        unsupported.add(key);
      }
    }
    if (!unsupported.isEmpty()) {
      throw new ValidationException(
          "Unsupported search filters: "
              + unsupported
              + ". Supported: "
              + searchable);
    }
  }

  private static Set<String> searchableKeys(VerticalSchema schema) {
    Set<String> keys = new HashSet<>();
    if (schema.getEntities() == null) {
      return keys;
    }
    var inventory = schema.getEntities().get("inventory");
    if (inventory == null || inventory.getFields() == null) {
      return keys;
    }
    for (VerticalSchemaField field : inventory.getFields()) {
      if (Boolean.TRUE.equals(field.getSearchable())) {
        keys.add(field.getKey());
      }
    }
    keys.add("expiryBefore");
    keys.add("expiryAfter");
    keys.add("nearExpiryDays");
    return keys;
  }
}
