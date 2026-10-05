package com.inventory.product.labels;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a catalog field may be used. Serialized in lowercase to match the frontend {@code
 * FieldUsage} type ({@code label|card}).
 *
 * <p>The Field_Catalog is a single vocabulary shared by barcode stickers and product cards; each
 * field declares the surfaces it makes sense on. Shop identity fields (shop name, GSTIN, …) are
 * {@link #LABEL} only; stock counts and purchase figures are {@link #CARD} only; most product, lot
 * and pricing fields are both.
 */
public enum FieldUsage {
  /** Printable on a barcode sticker. */
  LABEL("label"),
  /** Showable on a product card (search result, Scan &amp; Sell row, ingredient card). */
  CARD("card"),
  /** Usable as a filter, facet or sort key in advanced product search. */
  SEARCH("search");

  private final String wireName;

  FieldUsage(String wireName) {
    this.wireName = wireName;
  }

  /** Lowercase name used in API payloads. */
  @JsonValue
  public String wireName() {
    return wireName;
  }
}
