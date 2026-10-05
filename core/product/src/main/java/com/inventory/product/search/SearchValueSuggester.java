package com.inventory.product.search;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Distinct values of a text facet field for the "Find a company…" box (advanced-product-search
 * R5.4, R10.4). Answers from a 60-second per-shop cache of the field's distinct values, filtered by
 * the typed prefix-or-contains, so repeated keystrokes cost no database reads.
 */
@Component
public class SearchValueSuggester {

  static final int MAX_RESULTS = 20;
  static final Duration TTL = Duration.ofSeconds(60);
  static final int MAX_DISTINCT = 5_000;

  private record CacheEntry(Instant loadedAt, List<String> values) {}

  private final MongoTemplate mongoTemplate;
  private final SearchFieldCatalogService catalogService;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public SearchValueSuggester(MongoTemplate mongoTemplate, SearchFieldCatalogService catalogService) {
    this.mongoTemplate = mongoTemplate;
    this.catalogService = catalogService;
  }

  public List<String> suggest(String shopId, String fieldKey, String typed, Integer limit) {
    ShopSearchContext ctx = catalogService.context(shopId);
    PrintableField field =
        ctx.catalog()
            .findForUsage(fieldKey, FieldUsage.SEARCH)
            .orElseThrow(() -> new ValidationException("Unknown search field '" + fieldKey + "'"));
    SearchSpec spec = field.searchSpec();
    if (spec.type() != SearchFieldType.TEXT || !spec.facetable()) {
      throw new ValidationException("Values can only be suggested for text fields that show counts");
    }
    int max = limit == null || limit <= 0 ? MAX_RESULTS : Math.min(limit, MAX_RESULTS);
    List<String> all = distinctValues(shopId, spec);
    String q = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) {
      return all.subList(0, Math.min(max, all.size()));
    }
    // prefix matches first, then contains, each in alphabetical order
    List<String> prefix = new ArrayList<>();
    List<String> contains = new ArrayList<>();
    for (String v : all) {
      String lower = v.toLowerCase(Locale.ROOT);
      if (lower.startsWith(q)) {
        prefix.add(v);
      } else if (lower.contains(q)) {
        contains.add(v);
      }
      if (prefix.size() >= max) {
        break;
      }
    }
    List<String> out = new ArrayList<>(prefix);
    for (String v : contains) {
      if (out.size() >= max) break;
      out.add(v);
    }
    return out;
  }

  private List<String> distinctValues(String shopId, SearchSpec spec) {
    String key = shopId + "|" + spec.path();
    CacheEntry hit = cache.get(key);
    if (hit != null && hit.loadedAt().plus(TTL).isAfter(Instant.now())) {
      return hit.values();
    }
    List<String> loaded = loadDistinct(shopId, spec);
    cache.put(key, new CacheEntry(Instant.now(), loaded));
    return loaded;
  }

  /** Reads distinct values from the owning collection; de-duplicates ignoring case; sorts. */
  private List<String> loadDistinct(String shopId, SearchSpec spec) {
    String collection;
    String fieldPath;
    switch (spec.source()) {
      case PRODUCT -> {
        collection = MongoPreQueryRunner.PRODUCT_COLLECTION;
        fieldPath = InventorySearchPlanner.stripAlias(spec.path(), InventorySearchPlanner.PRODUCT_ALIAS);
        if ("normalizedName".equals(fieldPath)) fieldPath = "name";
      }
      case LOT -> {
        collection = InventorySearchEngine.INVENTORY_COLLECTION;
        fieldPath = spec.path();
      }
      default -> {
        return List.of(); // vertical text facets are not offered for typeahead in this version
      }
    }
    MongoCollection<Document> col = mongoTemplate.getCollection(collection);
    Map<String, String> byLower = new TreeMap<>();
    for (String v :
        col.distinct(fieldPath, new Document("shopId", shopId).append(fieldPath, new Document("$ne", null)), String.class)
            .maxTime(2, TimeUnit.SECONDS)) {
      if (!StringUtils.hasText(v)) continue;
      String trimmed = v.trim();
      byLower.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
      if (byLower.size() >= MAX_DISTINCT) break;
    }
    List<String> out = new ArrayList<>(byLower.values());
    out.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
    return out;
  }

  /** For tests. */
  void clearCache() {
    cache.clear();
  }
}
