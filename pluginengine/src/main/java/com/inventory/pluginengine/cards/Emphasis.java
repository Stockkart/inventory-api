package com.inventory.pluginengine.cards;

import java.util.Optional;

/** Visual weight of one field on a card line. */
public enum Emphasis {
  /** Default body text. */
  NORMAL,
  /** Bold, used for prices today. */
  STRONG,
  /** De-emphasised, used for descriptions today. */
  MUTED;

  /** Lenient parse used when reading persisted documents; unknown or null yields empty. */
  public static Optional<Emphasis> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (Emphasis e : values()) {
      if (e.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(e);
      }
    }
    return Optional.empty();
  }
}
