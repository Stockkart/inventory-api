package com.inventory.pluginengine.cards;

import java.util.Optional;

/** What a card does with a field whose value is blank for a given item. */
public enum CardBlankValueBehavior {
  /** Drop the field; drop the line when every field on it is blank. Today's behaviour. */
  HIDE_LINE,
  /** Render an em dash so every card from the same layout has the same lines. */
  SHOW_DASH;

  /** Lenient parse used when reading persisted documents; unknown or null yields empty. */
  public static Optional<CardBlankValueBehavior> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (CardBlankValueBehavior b : values()) {
      if (b.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(b);
      }
    }
    return Optional.empty();
  }
}
