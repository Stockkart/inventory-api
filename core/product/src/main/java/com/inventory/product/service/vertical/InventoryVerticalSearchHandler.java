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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class InventoryVerticalSearchHandler {

  public record VerticalSearchPage(List<Inventory> items, String nextCursor) {}

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

  /**
   * Search with sort + pagination applied in the extension Mongo query (not in application memory).
   */
  public VerticalSearchPage searchPage(
      String shopId,
      String q,
      Map<String, String> filters,
      String sort,
      int limit,
      String cursor,
      int skip) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return textOnlyCorePage(shopId, q, limit, skip);
    }

    validateFilters(shop, filters);

    Optional<InventorySearchProvider> providerOpt =
        pluginRegistry.find(shop.getVerticalId()).flatMap(p -> p.getSearchProvider());
    if (providerOpt.isEmpty()) {
      return textOnlyCorePage(shopId, q, limit, skip);
    }

    Set<String> restrictIds = resolveRestrictInventoryIds(shopId, q);
    if (restrictIds != null && restrictIds.isEmpty()) {
      return new VerticalSearchPage(List.of(), null);
    }

    InventorySearchResult result =
        providerOpt
            .get()
            .search(
                shopId,
                InventorySearchQuery.builder()
                    .filters(filters != null ? filters : Map.of())
                    .sort(sort)
                    .limit(limit)
                    .cursor(cursor)
                    .skip(skip)
                    .restrictInventoryIds(restrictIds)
                    .schema(schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion()))
                    .build());

    List<Inventory> items = loadInventoriesOrdered(shopId, result.getInventoryIds());
    return new VerticalSearchPage(items, result.getNextCursor());
  }

  /** List shop inventory ordered by vertical sort (expiry for medical) via extension index. */
  public VerticalSearchPage listPage(String shopId, String sort, int limit, int skip) {
    return searchPage(shopId, null, Map.of(), sort, limit, null, skip);
  }

  private Set<String> resolveRestrictInventoryIds(String shopId, String q) {
    if (!StringUtils.hasText(q)) {
      return null;
    }
    return inventoryRepository.searchByShopIdAndQuery(shopId, q.trim()).stream()
        .map(Inventory::getId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
  }

  private List<Inventory> loadInventoriesOrdered(String shopId, List<String> orderedIds) {
    if (orderedIds == null || orderedIds.isEmpty()) {
      return List.of();
    }
    List<Inventory> loaded = inventoryRepository.findByIdIn(orderedIds);
    Map<String, Inventory> byId = new LinkedHashMap<>();
    for (Inventory inv : loaded) {
      if (inv != null && shopId.equals(inv.getShopId()) && StringUtils.hasText(inv.getId())) {
        byId.put(inv.getId(), inv);
      }
    }
    List<Inventory> ordered = new ArrayList<>();
    for (String id : orderedIds) {
      Inventory inv = byId.get(id);
      if (inv != null) {
        ordered.add(inv);
      }
    }
    return ordered;
  }

  private VerticalSearchPage textOnlyCorePage(String shopId, String q, int limit, int skip) {
    int effectiveLimit = limit > 0 ? limit : 50;
    if (!StringUtils.hasText(q)) {
      int page = effectiveLimit > 0 ? skip / effectiveLimit : 0;
      List<Inventory> pageItems =
          inventoryRepository.findByShopId(
              shopId, org.springframework.data.domain.PageRequest.of(page, effectiveLimit));
      return new VerticalSearchPage(pageItems, null);
    }
    List<Inventory> matches = inventoryRepository.searchByShopIdAndQuery(shopId, q.trim());
    int from = Math.max(skip, 0);
    if (from >= matches.size()) {
      return new VerticalSearchPage(List.of(), null);
    }
    int to = Math.min(from + effectiveLimit, matches.size());
    return new VerticalSearchPage(matches.subList(from, to), null);
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
