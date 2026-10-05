package com.inventory.product.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * How one filter group compares its field against its values (advanced-product-search R2.2).
 *
 * <ul>
 *   <li>{@link #IN} — the value is one of the listed values (text compared ignoring case).
 *   <li>{@link #MATCHES} — the value matches a text pattern (same rules as the search text).
 *   <li>{@link #BETWEEN} — number or date between {@code from} and {@code to}; either side optional.
 *   <li>{@link #WITHIN_DAYS} — date between now and now + N days.
 *   <li>{@link #EXISTS} — the field has a value.
 * </ul>
 */
public enum FilterOp {
  IN("in"),
  MATCHES("matches"),
  BETWEEN("between"),
  WITHIN_DAYS("withinDays"),
  EXISTS("exists");

  private final String wireName;

  FilterOp(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }

  /** Lenient parse of the wire name or enum name; empty when unknown. */
  @JsonCreator
  public static FilterOp fromWire(String raw) {
    return parse(raw).orElse(null);
  }

  public static Optional<FilterOp> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (FilterOp op : values()) {
      if (op.wireName.equalsIgnoreCase(trimmed) || op.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(op);
      }
    }
    return Optional.empty();
  }
}
