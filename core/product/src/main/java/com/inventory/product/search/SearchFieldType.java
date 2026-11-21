package com.inventory.product.search;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Set;

/** The kind of value a search field holds; decides the operators it accepts and the UI control. */
public enum SearchFieldType {
  TEXT("text", Set.of(FilterOp.IN, FilterOp.MATCHES, FilterOp.EXISTS)),
  NUMBER("number", Set.of(FilterOp.BETWEEN, FilterOp.EXISTS)),
  DATE("date", Set.of(FilterOp.BETWEEN, FilterOp.WITHIN_DAYS, FilterOp.EXISTS)),
  ENUM("enum", Set.of(FilterOp.IN));

  private final String wireName;
  private final Set<FilterOp> operators;

  SearchFieldType(String wireName, Set<FilterOp> operators) {
    this.wireName = wireName;
    this.operators = operators;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }

  /** The operators a field of this type accepts. */
  public Set<FilterOp> operators() {
    return operators;
  }
}
