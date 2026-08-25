package com.inventory.product.service.vertical;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.utils.InventoryFreeTextSearch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
  private final MongoTemplate mongoTemplate;

  public InventoryVerticalSearchHandler(
      ShopRepository shopRepository,
      PluginRegistry pluginRegistry,
      SchemaLoader schemaLoader,
      InventoryRepository inventoryRepository,
      MongoTemplate mongoTemplate) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
    this.schemaLoader = schemaLoader;
    this.inventoryRepository = inventoryRepository;
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Free-text search: substring on name / company / location; prefix on barcode / HSN / batch.
   */
  private List<Inventory> searchInventoryByText(String shopId, String q) {
    return searchInventoryByText(shopId, q, true);
  }

  private List<Inventory> searchInventoryByText(String shopId, String q, boolean includeZeroStock) {
    String query = q.trim();
    String contains = InventoryFreeTextSearch.containsPattern(query);
    List<String> identifierTokens = InventoryFreeTextSearch.identifierTokens(query);
    List<Criteria> identifierPrefix = new ArrayList<>();
    for (String token : identifierTokens) {
      String prefix = InventoryFreeTextSearch.prefixPattern(token);
      identifierPrefix.add(Criteria.where("barcode").regex(prefix, "i"));
      identifierPrefix.add(Criteria.where("hsn").regex(prefix, "i"));
    }

    Set<String> productIds = new LinkedHashSet<>();
    List<Criteria> productOr = new ArrayList<>();
    productOr.add(Criteria.where("name").regex(contains, "i"));
    productOr.add(Criteria.where("companyName").regex(contains, "i"));
    productOr.addAll(identifierPrefix);
    Query productQuery =
        Query.query(
            new Criteria()
                .andOperator(
                    Criteria.where("shopId").is(shopId),
                    new Criteria().orOperator(productOr.toArray(Criteria[]::new))));
    productQuery.fields().include("_id");
    productQuery.limit(500);
    for (Product product : mongoTemplate.find(productQuery, Product.class)) {
      if (StringUtils.hasText(product.getId())) {
        productIds.add(product.getId());
      }
    }

    Set<String> inventoryIds = new LinkedHashSet<>();
    if (!productIds.isEmpty()) {
      for (Inventory inv : inventoryRepository.findByShopIdAndProductIdIn(shopId, productIds)) {
        if (inv != null && StringUtils.hasText(inv.getId())) {
          inventoryIds.add(inv.getId());
        }
      }
    }

    List<Criteria> lotOr = new ArrayList<>();
    lotOr.add(Criteria.where("location").regex(contains, "i"));
    for (String token : identifierTokens) {
      lotOr.add(Criteria.where("batchNo").regex(InventoryFreeTextSearch.prefixPattern(token), "i"));
    }
    Query lotQuery =
        Query.query(
            new Criteria()
                .andOperator(
                    Criteria.where("shopId").is(shopId),
                    new Criteria().orOperator(lotOr.toArray(Criteria[]::new))));
    lotQuery.fields().include("_id");
    lotQuery.limit(500);
    for (Inventory inv : mongoTemplate.find(lotQuery, Inventory.class)) {
      if (inv != null && StringUtils.hasText(inv.getId())) {
        inventoryIds.add(inv.getId());
      }
    }

    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop != null && StringUtils.hasText(shop.getVerticalId())) {
      Optional<InventorySearchProvider> provider =
          pluginRegistry.find(shop.getVerticalId()).flatMap(p -> p.getSearchProvider());
      if (provider.isPresent()) {
        for (String token : identifierTokens) {
          inventoryIds.addAll(provider.get().findInventoryIdsByBatchPrefix(shopId, token));
        }
      }
    }

    if (inventoryIds.isEmpty()) {
      return List.of();
    }
    return inStockOnly(loadInventoriesOrdered(shopId, new ArrayList<>(inventoryIds)), includeZeroStock);
  }

  /**
   * Drops sold-out lots. Applied to the loaded list rather than the Mongo query because the text
   * search unions matches from three collections; filtering once at the end keeps the paging in
   * {@link #textOnlyCorePage} counting the same rows the caller will see.
   */
  private static List<Inventory> inStockOnly(List<Inventory> items, boolean includeZeroStock) {
    if (includeZeroStock || items.isEmpty()) {
      return items;
    }
    return items.stream()
        .filter(
            inv ->
                inv != null
                    && inv.getCurrentCount() != null
                    && inv.getCurrentCount().compareTo(java.math.BigDecimal.ZERO) > 0)
        .toList();
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
    return searchPage(shopId, q, filters, sort, limit, cursor, skip, true);
  }

  /**
   * @param includeZeroStock when false, sold-out lots are left out of the page. The selling screens
   *     pass false; stock correction and pricing need the sold-out lots and pass true.
   */
  public VerticalSearchPage searchPage(
      String shopId,
      String q,
      Map<String, String> filters,
      String sort,
      int limit,
      String cursor,
      int skip,
      boolean includeZeroStock) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return textOnlyCorePage(shopId, q, limit, skip, includeZeroStock);
    }

    validateFilters(shop, filters);

    Optional<InventorySearchProvider> providerOpt =
        pluginRegistry.find(shop.getVerticalId()).flatMap(p -> p.getSearchProvider());
    if (providerOpt.isEmpty()) {
      return textOnlyCorePage(shopId, q, limit, skip, includeZeroStock);
    }

    // With a text query the stock gate is already applied here, so the extension query is
    // restricted to in-stock ids and its limit fills a whole page. Filter-only searches have no id
    // restriction, so those pages are trimmed after loading and can come back short.
    Set<String> restrictIds = resolveRestrictInventoryIds(shopId, q, includeZeroStock);
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

    List<String> extensionIds =
        result.getInventoryIds() != null ? result.getInventoryIds() : List.of();
    List<Inventory> items = inStockOnly(loadInventoriesOrdered(shopId, extensionIds), includeZeroStock);
    boolean noFilters = filters == null || filters.isEmpty();
    // Extension docs can point at missing inventory (orphans). Offset pages then under-fill
    // (e.g. page 0 → 1 row, page 2 → 50). Fall back when nothing loads, or when an unfiltered
    // page lost rows to orphans — skip on extension ≠ skip on inventory.
    boolean orphanedPage =
        includeZeroStock && !extensionIds.isEmpty() && items.size() < extensionIds.size();
    if (items.isEmpty() || (orphanedPage && noFilters)) {
      if (noFilters) {
        log.warn(
            "[inventory-search] extension search returned {} ids but only {} loadable for shop {} "
                + "(q={}); falling back to core inventory",
            extensionIds.size(),
            items.size(),
            shopId,
            q);
        return textOnlyCorePage(shopId, q, limit, skip, includeZeroStock);
      }
    }
    return new VerticalSearchPage(items, result.getNextCursor());
  }

  /**
   * Offset list pages the inventory collection directly. Do not drive list offsets off
   * {@code inventory_ext_*} — orphaned extension rows make page sizes uneven and hide lots that
   * never got an extension document. Vertical filters/sort still go through {@link #searchPage}.
   */
  public VerticalSearchPage listPage(String shopId, String sort, int limit, int skip) {
    return listPage(shopId, sort, limit, skip, true);
  }

  public VerticalSearchPage listPage(
      String shopId, String sort, int limit, int skip, boolean includeZeroStock) {
    return textOnlyCorePage(shopId, null, limit, skip, includeZeroStock);
  }

  private Set<String> resolveRestrictInventoryIds(
      String shopId, String q, boolean includeZeroStock) {
    if (!StringUtils.hasText(q)) {
      return null;
    }
    return searchInventoryByText(shopId, q.trim(), includeZeroStock).stream()
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

  private VerticalSearchPage textOnlyCorePage(
      String shopId, String q, int limit, int skip, boolean includeZeroStock) {
    int effectiveLimit = limit > 0 ? limit : 50;
    if (!StringUtils.hasText(q)) {
      int page = effectiveLimit > 0 ? skip / effectiveLimit : 0;
      org.springframework.data.domain.PageRequest pageRequest =
          org.springframework.data.domain.PageRequest.of(
              page,
              effectiveLimit,
              org.springframework.data.domain.Sort.by(
                  org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
      List<Inventory> pageItems =
          includeZeroStock
              ? inventoryRepository.findByShopId(shopId, pageRequest)
              : inventoryRepository.findByShopIdAndCurrentCountGreaterThan(
                  shopId, java.math.BigDecimal.ZERO, pageRequest);
      return new VerticalSearchPage(pageItems, null);
    }
    List<Inventory> matches = searchInventoryByText(shopId, q.trim(), includeZeroStock);
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
