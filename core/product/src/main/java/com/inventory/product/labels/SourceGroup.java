package com.inventory.product.labels;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a printable field's value comes from. Serialized in lowercase to match the frontend
 * {@code LabelSourceGroup} type ({@code product|lot|pricing|shop|vertical}).
 *
 * <p>Catalog group order is fixed to {@code product, pricing, lot, vertical, shop} (Req 1.7); the
 * declaration order here is that catalog order so {@link #ordinal()} can be used for sorting.
 */
public enum SourceGroup {
  PRODUCT("product"),
  PRICING("pricing"),
  LOT("lot"),
  VERTICAL("vertical"),
  SHOP("shop");

  private final String wireName;

  SourceGroup(String wireName) {
    this.wireName = wireName;
  }

  /** Lowercase name used in API payloads. */
  @JsonValue
  public String wireName() {
    return wireName;
  }
}
