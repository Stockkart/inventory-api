package com.inventory.product.search;

import java.util.List;
import java.util.Map;
import org.bson.Document;

/**
 * The aggregation pipeline the engine runs, plus what it needs to know to read the answer.
 *
 * @param stages the pipeline on the {@code inventory} collection
 * @param facetKeys catalog field keys in the order their {@code $facet} outputs appear
 * @param facetOutputNames facet field key → name of its array in the {@code $facet} result
 * @param productJoined whether the product join stage is present
 * @param extensionJoined whether the extension join stage is present
 * @param shortCircuit when true the planner already knows the answer is empty (a pre-query that
 *     must match returned nothing) and the pipeline need not run
 * @param productFacetPaths facet field key → product field name, for facets the pipeline grouped by
 *     {@code productId} (no product join); the engine maps ids to values afterwards
 * @param deferredSort when non-null the pipeline returns every candidate id (unsorted, unpaged) under
 *     {@link #RESULTS} and the engine sorts them by a key read from another collection, then pages
 */
public record SearchPlan(
    List<Document> stages,
    List<String> facetKeys,
    Map<String, String> facetOutputNames,
    boolean productJoined,
    boolean extensionJoined,
    boolean shortCircuit,
    Map<String, String> productFacetPaths,
    DeferredSort deferredSort) {

  public static final String RESULTS = "results";
  public static final String TOTAL = "total";

  /**
   * A sort whose key lives on another collection. Joining 50,000 lots costs seconds; reading the key
   * for the candidates with one indexed {@code $in} query and ordering in memory costs tens to a few
   * hundred milliseconds.
   *
   * @param source where the key lives
   * @param shopId the shop, so a very large candidate set can be served by one index-ordered scan of
   *     the shop's rows instead of a huge {@code $in}
   * @param collection the collection to read the key from
   * @param foreignKey the field there that identifies the lot ({@code _id} for product via {@code
   *     productId}, {@code inventoryId} for extensions)
   * @param localKey the lot field that joins to {@code foreignKey} ({@code productId} or {@code _id})
   * @param valueField the field holding the sort value
   * @param ascending direction
   */
  public record DeferredSort(
      SearchSource source,
      String shopId,
      String collection,
      String foreignKey,
      String localKey,
      String valueField,
      boolean ascending) {}

  public SearchPlan {
    stages = stages == null ? List.of() : List.copyOf(stages);
    facetKeys = facetKeys == null ? List.of() : List.copyOf(facetKeys);
    facetOutputNames = facetOutputNames == null ? Map.of() : Map.copyOf(facetOutputNames);
    productFacetPaths = productFacetPaths == null ? Map.of() : Map.copyOf(productFacetPaths);
  }

  public static SearchPlan empty(List<String> facetKeys, Map<String, String> facetOutputNames) {
    return new SearchPlan(List.of(), facetKeys, facetOutputNames, false, false, true, Map.of(), null);
  }

  public boolean sortsInMemory() {
    return deferredSort != null;
  }
}
