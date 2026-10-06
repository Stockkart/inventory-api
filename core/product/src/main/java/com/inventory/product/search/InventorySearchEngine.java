package com.inventory.product.search;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import com.inventory.product.rest.dto.response.PageMeta;
import com.inventory.product.rest.dto.response.SearchResponse;
import com.inventory.product.rest.dto.response.SearchResponse.FacetValue;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.client.MongoCollection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Runs an advanced search end to end (advanced-product-search R3.4, R4.2, R4.3, R4.6, R5.3, R5.5,
 * R11): validate → plan → one aggregation with a time limit → load the page of lots in order →
 * enrich exactly as every other inventory list → fill zero-count enum facet values.
 *
 * <p>Enrichment is injected as a function so this class does not depend on {@code InventoryService}
 * (which depends on it); the service passes its own {@code toSummariesWithExtensions}.
 */
@Component
@Slf4j
public class InventorySearchEngine {

  static final String INVENTORY_COLLECTION = "inventory";
  static final long MAX_TIME_MS = 3_000;
  static final String TOO_SLOW = "This search is too slow — please narrow it down";

  private final SearchFieldCatalogService catalogService;
  private final SearchRequestValidator validator;
  private final InventorySearchPlanner planner;
  private final DeferredSortRunner deferredSort;
  private final MongoTemplate mongoTemplate;
  private final InventoryRepository inventoryRepository;
  private final SearchMetrics metrics;
  private final AtomicLong counter = new AtomicLong();

  public InventorySearchEngine(
      SearchFieldCatalogService catalogService,
      SearchRequestValidator validator,
      InventorySearchPlanner planner,
      DeferredSortRunner deferredSort,
      MongoTemplate mongoTemplate,
      InventoryRepository inventoryRepository,
      SearchMetrics metrics) {
    this.catalogService = catalogService;
    this.validator = validator;
    this.planner = planner;
    this.deferredSort = deferredSort;
    this.mongoTemplate = mongoTemplate;
    this.inventoryRepository = inventoryRepository;
    this.metrics = metrics;
  }

  /** The shop's search context (catalog + default sort), for callers that translate requests. */
  public ShopSearchContext context(String shopId) {
    return catalogService.context(shopId);
  }

  /**
   * @param enrich turns loaded lots into summaries (product identity, pricing, vertical fields,
   *     available stock) — {@code InventoryService::toSummariesWithExtensions}
   */
  public SearchResponse search(
      String shopId, SearchRequest request, BiFunction<String, List<Inventory>, List<InventorySummaryDto>> enrich) {
    long started = System.nanoTime();
    ShopSearchContext ctx = catalogService.context(shopId);
    String surface = request != null && StringUtils.hasText(request.surface()) ? request.surface() : "product-search";

    ValidatedSearch search;
    try {
      search = validator.validate(request, ctx);
    } catch (ValidationException e) {
      metrics.failed(surface, isRegexRejection(e) ? "regex_rejected" : "validation_error");
      throw e;
    }
    SearchMetrics.Tags tags = SearchMetrics.Tags.of(search);

    try {
      SearchPlan plan = planner.plan(search, ctx);
      Document raw;
      long dbStarted = System.nanoTime();
      if (plan.shortCircuit()) {
        raw = new Document();
      } else {
        raw = runPipeline(plan);
        if (plan.sortsInMemory() && candidateCount(raw) > InventorySearchPlanner.MAX_DEFERRED_CANDIDATES) {
          // Too many candidates to order in memory: take the slow, bounded path through the join.
          plan = planner.plan(search, ctx, true);
          raw = runPipeline(plan);
        }
      }

      List<String> ids = plan.sortsInMemory() ? sortAndPage(raw, plan, search) : resultIds(raw);
      long dbMs = (System.nanoTime() - dbStarted) / 1_000_000;
      long total = totalCount(raw);
      Map<String, List<FacetValue>> facets = facets(raw, plan, search);

      List<Inventory> lots = loadOrdered(shopId, ids);
      List<InventorySummaryDto> summaries = enrich.apply(shopId, lots);

      int totalPages = total > 0 ? (int) Math.ceil((double) total / search.size()) : 0;
      PageMeta pageMeta = new PageMeta(search.page(), search.size(), total, totalPages);

      long totalMs = (System.nanoTime() - started) / 1_000_000;
      record(search, plan, tags, dbMs, totalMs, total, shopId);
      return new SearchResponse(summaries, pageMeta, facets, search.sort() != null ? search.sort().wire() : null);
    } catch (MongoExecutionTimeoutException e) {
      metrics.failed(search.surface(), "timeout");
      throw new ValidationException(TOO_SLOW);
    } catch (ValidationException e) {
      throw e;
    } catch (RuntimeException e) {
      metrics.failed(search.surface(), "error");
      log.error("[search] failed for shop {}: {}", shopId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to search inventory");
    }
  }

  // ---- pipeline ------------------------------------------------------------------------------------

  private Document runPipeline(SearchPlan plan) {
    MongoCollection<Document> col = mongoTemplate.getCollection(INVENTORY_COLLECTION);
    Document first = col.aggregate(plan.stages()).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).allowDiskUse(false).first();
    return first == null ? new Document() : first;
  }

  @SuppressWarnings("unchecked")
  private static List<String> resultIds(Document raw) {
    List<Document> results = (List<Document>) raw.getOrDefault(SearchPlan.RESULTS, List.of());
    List<String> ids = new ArrayList<>(results.size());
    for (Document d : results) {
      Object id = d.get("_id");
      if (id != null) {
        ids.add(id.toString());
      }
    }
    return ids;
  }

  @SuppressWarnings("unchecked")
  private static int candidateCount(Document raw) {
    return ((List<Document>) raw.getOrDefault(SearchPlan.RESULTS, List.of())).size();
  }

  @SuppressWarnings("unchecked")
  private List<String> sortAndPage(Document raw, SearchPlan plan, ValidatedSearch search) {
    List<Document> candidates = (List<Document>) raw.getOrDefault(SearchPlan.RESULTS, List.of());
    return deferredSort.sortAndPage(candidates, plan.deferredSort(), search.skip(), search.size(), MAX_TIME_MS);
  }

  @SuppressWarnings("unchecked")
  private static long totalCount(Document raw) {
    List<Document> total = (List<Document>) raw.getOrDefault(SearchPlan.TOTAL, List.of());
    if (total.isEmpty()) {
      return 0;
    }
    Number n = total.get(0).get("n", Number.class);
    return n == null ? 0 : n.longValue();
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<FacetValue>> facets(Document raw, SearchPlan plan, ValidatedSearch search) {
    Map<String, List<FacetValue>> out = new LinkedHashMap<>();
    for (PrintableField field : search.facets()) {
      String key = field.fieldKey();
      String output = plan.facetOutputNames().get(key);
      List<Document> rows = output == null ? List.of() : (List<Document>) raw.getOrDefault(output, List.of());
      Map<String, Long> counts = new LinkedHashMap<>();
      String productField = plan.productFacetPaths().get(key);
      if (productField != null) {
        counts = countsByProductField(rows, productField);
      } else {
        for (Document d : rows) {
          Object v = d.get("_id");
          Number c = d.get("count", Number.class);
          if (v != null && c != null) {
            counts.put(v.toString(), c.longValue());
          }
        }
      }
      List<FacetValue> values = new ArrayList<>();
      SearchSpec spec = field.searchSpec();
      if (spec.type() == SearchFieldType.ENUM) {
        // Every allowed value, including zero counts, in catalog order (R5.3).
        for (SearchSpec.EnumValue ev : spec.enumValues()) {
          values.add(new FacetValue(ev.value(), ev.label(), counts.getOrDefault(ev.value(), 0L)));
        }
      } else {
        counts.forEach((v, c) -> values.add(new FacetValue(v, v, c)));
      }
      out.put(key, values);
      metrics.facet(key);
    }
    return out;
  }

  /**
   * Facets on product fields are grouped by {@code productId} in the pipeline (no join); here the
   * ids are mapped to the field value with one indexed read and summed per value, top 25.
   */
  private Map<String, Long> countsByProductField(List<Document> rows, String productField) {
    Map<String, Long> byProductId = new LinkedHashMap<>();
    for (Document d : rows) {
      Object v = d.get("_id");
      Number c = d.get("count", Number.class);
      if (v != null && c != null) {
        byProductId.put(v.toString(), c.longValue());
      }
    }
    if (byProductId.isEmpty()) {
      return Map.of();
    }
    List<org.bson.types.ObjectId> oids = new ArrayList<>();
    for (String id : byProductId.keySet()) {
      if (org.bson.types.ObjectId.isValid(id)) {
        oids.add(new org.bson.types.ObjectId(id));
      }
    }
    Map<String, Long> byValue = new java.util.HashMap<>();
    MongoCollection<Document> products = mongoTemplate.getCollection("product");
    for (Document p :
        products.find(new Document("_id", new Document("$in", oids))).projection(new Document(productField, 1)).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)) {
      Object value = p.get(productField);
      Long count = byProductId.get(p.getObjectId("_id").toHexString());
      if (value != null && count != null) {
        byValue.merge(value.toString(), count, Long::sum);
      }
    }
    return byValue.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .limit(InventorySearchPlanner.FACET_VALUE_LIMIT)
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  private List<Inventory> loadOrdered(String shopId, List<String> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    Map<String, Inventory> byId = new LinkedHashMap<>();
    for (Inventory inv : inventoryRepository.findByIdIn(ids)) {
      if (inv != null && shopId.equals(inv.getShopId()) && StringUtils.hasText(inv.getId())) {
        byId.put(inv.getId(), inv);
      }
    }
    List<Inventory> ordered = new ArrayList<>(ids.size());
    for (String id : ids) {
      Inventory inv = byId.get(id);
      if (inv != null) {
        ordered.add(inv);
      }
    }
    return ordered;
  }

  // ---- metrics -------------------------------------------------------------------------------------

  private void record(ValidatedSearch search, SearchPlan plan, SearchMetrics.Tags tags, long dbMs, long totalMs, long total, String shopId) {
    metrics.completed(tags, totalMs, dbMs, total);
    long n = counter.incrementAndGet();
    long examined = -1;
    long returned = -1;
    if (!plan.shortCircuit() && (SearchMetrics.shouldSampleExplain(n) || totalMs >= SearchMetrics.SLOW_THRESHOLD_MS)) {
      long[] stats = explainStats(plan);
      examined = stats[0];
      returned = stats[1];
      if (examined >= 0) {
        metrics.explainSample(search.surface(), examined, returned);
      }
    }
    if (totalMs >= SearchMetrics.SLOW_THRESHOLD_MS) {
      metrics.slow(search, plan, dbMs, totalMs, examined, returned, shopId);
    }
  }

  /** {@code [docsExamined, nReturned]} from {@code explain("executionStats")}; {@code [-1,-1]} on failure. */
  @SuppressWarnings("unchecked")
  long[] explainStats(SearchPlan plan) {
    try {
      Document cmd =
          new Document("explain", new Document("aggregate", INVENTORY_COLLECTION).append("pipeline", plan.stages()).append("cursor", new Document()))
              .append("verbosity", "executionStats");
      Document explain = mongoTemplate.getDb().runCommand(cmd);
      Document stats = findExecutionStats(explain);
      if (stats == null) {
        return new long[] {-1, -1};
      }
      Number examined = stats.get("totalDocsExamined", Number.class);
      Number returned = stats.get("nReturned", Number.class);
      return new long[] {examined == null ? -1 : examined.longValue(), returned == null ? -1 : returned.longValue()};
    } catch (RuntimeException e) {
      log.debug("[search] explain failed: {}", e.getMessage());
      return new long[] {-1, -1};
    }
  }

  @SuppressWarnings("unchecked")
  private static Document findExecutionStats(Document explain) {
    if (explain.containsKey("executionStats")) {
      return explain.get("executionStats", Document.class);
    }
    List<Document> stages = (List<Document>) explain.get("stages");
    if (stages != null && !stages.isEmpty()) {
      Document cursor = stages.get(0).get("$cursor", Document.class);
      if (cursor != null && cursor.containsKey("executionStats")) {
        return cursor.get("executionStats", Document.class);
      }
    }
    return null;
  }

  private static boolean isRegexRejection(ValidationException e) {
    String m = e.getMessage() == null ? "" : e.getMessage();
    return m.contains("Regular expression");
  }
}
