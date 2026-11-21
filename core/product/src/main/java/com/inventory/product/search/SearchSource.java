package com.inventory.product.search;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a search field's value lives, which decides at what stage of the pipeline it can be
 * matched (advanced-product-search R1.1, R10.2).
 */
public enum SearchSource {
  /** On the {@code product} document; reachable after the product join (or by a product pre-query). */
  PRODUCT("product"),
  /** On the {@code inventory} document; matchable before any join. */
  LOT("lot"),
  /** On the {@code inventory_ext_<vertical>} document; reachable after the extension join. */
  EXTENSION("vertical"),
  /** Derived in the pipeline (e.g. stock state); matchable after an {@code $addFields} stage. */
  COMPUTED("computed");

  private final String wireName;

  SearchSource(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }
}
