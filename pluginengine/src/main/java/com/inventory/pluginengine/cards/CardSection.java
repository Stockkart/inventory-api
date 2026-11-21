package com.inventory.pluginengine.cards;

import java.util.Arrays;
import java.util.List;

/**
 * An ordered group of card rows with an optional title and an optional divider above it.
 *
 * @param id unique within the layout; stable so the editor can address it
 * @param title optional heading (not used by the built-in defaults; reserved for denser surfaces)
 * @param dividerAbove render a horizontal rule before the section
 * @param rows the lines in the section, in display order
 */
public record CardSection(String id, String title, boolean dividerAbove, List<CardRow> rows) {

  public CardSection {
    title = title == null || title.isBlank() ? null : title.trim();
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  /** A section without divider or title. */
  public static CardSection of(String id, CardRow... rows) {
    return new CardSection(id, null, false, Arrays.asList(rows));
  }

  /** A section separated from the previous one by a divider. */
  public static CardSection divided(String id, CardRow... rows) {
    return new CardSection(id, null, true, Arrays.asList(rows));
  }
}
