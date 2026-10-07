package com.inventory.product.search;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.LabelFieldKeys;
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
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Values for the "Find…" box of a text filter (advanced-product-search R5.4, R10.4).
 *
 * <ul>
 *   <li>Fields with few values per shop (company, location — the ones that show counts) answer from
 *       a 60-second per-shop cache of the field's distinct values, filtered by the typed text, so
 *       repeated keystrokes cost no database reads.
 *   <li>Fields with one value per product or lot (name, barcode, HSN, batch) are looked up live:
 *       one indexed query on the shop's rows for values starting with — or, for names, containing —
 *       what was typed, capped at a small number of rows. Nothing is cached, because the list of
 *       thousands of names would be stale and mostly unused.
 * </ul>
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
    if (spec.type() != SearchFieldType.TEXT || !spec.operators().contains(FilterOp.IN)) {
      throw new ValidationException("Values can only be suggested for text fields");
    }
    int max = limit == null || limit <= 0 ? MAX_RESULTS : Math.min(limit, MAX_RESULTS);
    String q = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
    if (!spec.facetable() || ONE_PER_PRODUCT.contains(fieldKey)) {
      return lookupLive(shopId, spec, q, max);
    }
    List<String> all = distinctValues(shopId, spec);
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

  /** Live lookup for one-per-product fields: indexed regex on the typed text, a few hundred rows at most. */
  private List<String> lookupLive(String shopId, SearchSpec spec, String q, int max) {
    Target t = target(spec);
    if (t == null) {
      return List.of();
    }
    Document filter = new Document("shopId", shopId).append(t.path(), new Document("$ne", null));
    if (!q.isEmpty()) {
      // names match anywhere; identifiers (barcode, HSN, batch) from the start — same rule as the search
      String anchored = NAME_PATH.equals(t.path()) ? "" : "^";
      filter.put(t.matchPath(), new Document("$regex", anchored + Pattern.quote(q)).append("$options", "i"));
    }
    MongoCollection<Document> col = mongoTemplate.getCollection(t.collection());
    Map<String, String> byLower = new TreeMap<>();
    for (Document d :
        col.find(filter)
            .projection(new Document(t.path(), 1))
            .sort(new Document(t.matchPath(), 1))
            .limit(LIVE_SCAN_ROWS)
            .maxTime(2, TimeUnit.SECONDS)) {
      String v = d.getString(t.path());
      if (!StringUtils.hasText(v)) continue;
      String trimmed = v.trim();
      byLower.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
      if (byLower.size() >= max) break;
    }
    return new ArrayList<>(byLower.values());
  }

  /** Where a product/lot text field lives and which path to match on (names match on the lowercase copy). */
  private record Target(String collection, String path, String matchPath) {}

  static final String NAME_PATH = "name";

  /** Counted in searches like any facet, but far too many distinct values to cache as a list. */
  static final java.util.Set<String> ONE_PER_PRODUCT =
      java.util.Set.of(LabelFieldKeys.PRODUCT_NAME, LabelFieldKeys.BARCODE_TEXT, LabelFieldKeys.HSN, LabelFieldKeys.BATCH_NO);
  static final int LIVE_SCAN_ROWS = 400;

  private static Target target(SearchSpec spec) {
    switch (spec.source()) {
      case PRODUCT -> {
        String path = InventorySearchPlanner.stripAlias(spec.path(), InventorySearchPlanner.PRODUCT_ALIAS);
        if ("normalizedName".equals(path)) {
          return new Target(MongoPreQueryRunner.PRODUCT_COLLECTION, NAME_PATH, "normalizedName");
        }
        return new Target(MongoPreQueryRunner.PRODUCT_COLLECTION, path, path);
      }
      case LOT -> {
        return new Target(InventorySearchEngine.INVENTORY_COLLECTION, spec.path(), spec.path());
      }
      case EXTENSION -> {
        return null; // vertical text fields are not offered for typeahead in this version
      }
      default -> {
        return null;
      }
    }
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
