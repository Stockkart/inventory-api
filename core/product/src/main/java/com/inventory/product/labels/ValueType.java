package com.inventory.product.labels;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Formatting category of a printable field's value. Serialized in lowercase to match the frontend
 * {@code LabelValueType} type ({@code text|number|currency|date|percentage}).
 */
public enum ValueType {
  TEXT("text"),
  NUMBER("number"),
  CURRENCY("currency"),
  DATE("date"),
  PERCENTAGE("percentage");

  private final String wireName;

  ValueType(String wireName) {
    this.wireName = wireName;
  }

  /** Lowercase name used in API payloads. */
  @JsonValue
  public String wireName() {
    return wireName;
  }
}
