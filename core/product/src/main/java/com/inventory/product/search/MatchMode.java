package com.inventory.product.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** How filter groups combine: every group must match, or at least one must. */
public enum MatchMode {
  ALL("all"),
  ANY("any");

  private final String wireName;

  MatchMode(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }

  @JsonCreator
  public static MatchMode fromWire(String raw) {
    return parse(raw).orElse(null);
  }

  public static Optional<MatchMode> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (MatchMode m : values()) {
      if (m.wireName.equalsIgnoreCase(trimmed) || m.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(m);
      }
    }
    return Optional.empty();
  }
}
