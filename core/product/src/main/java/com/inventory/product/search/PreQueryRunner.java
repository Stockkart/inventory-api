package com.inventory.product.search;

import java.util.List;
import org.bson.Document;

/**
 * The cheap, indexed lookups the planner runs before building the pipeline so the main query starts
 * from a narrow set of lots (advanced-product-search R10.2). Behind an interface so planner tests
 * can stub them.
 */
public interface PreQueryRunner {

  /**
   * The distinct values of a low-cardinality field in the shop (served by a distinct scan of the
   * {@code (shopId, field)} index, so it costs as many steps as there are values, not rows). Returns
   * {@code null} when there are more than {@code max} values and the caller should match another way.
   */
  List<String> distinctValues(String collection, String shopId, String field, int max);

  /** Ids of products in the shop matching the filter. */
  List<String> productIds(String shopId, Document filter);

  /** Inventory ids referenced by extension documents in the collection matching the filter. */
  List<String> extensionInventoryIds(String collection, String shopId, Document filter);
}
