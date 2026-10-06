package com.inventory.product.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** How the search text (and {@code matches} filter values) is interpreted. */
public enum TextMode {
  /** Literal text, {@code *} means anything, matched anywhere, ignoring case. The default. */
  PATTERN("pattern"),
  /** A real regular expression, after safety checks. */
  REGEX("regex");

  private final String wireName;

  TextMode(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }

  @JsonCreator
  public static TextMode fromWire(String raw) {
    return parse(raw).orElse(null);
  }

  public static Optional<TextMode> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (TextMode m : values()) {
      if (m.wireName.equalsIgnoreCase(trimmed) || m.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(m);
      }
    }
    return Optional.empty();
  }
}
