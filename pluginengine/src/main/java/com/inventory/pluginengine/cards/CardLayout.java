package com.inventory.pluginengine.cards;

import java.util.Arrays;
import java.util.List;

/**
 * The configuration of one card surface for one {@link CardVariant}: ordered sections plus options.
 *
 * <p>Only {@code fieldKey}s are carried — never labels, paths or types — so a layout stays valid
 * across catalog evolution; the resolver drops keys the catalog no longer knows.
 *
 * @param sections the sections in display order; may be empty
 * @param options layout-wide switches; never {@code null}
 */
public record CardLayout(List<CardSection> sections, CardOptions options) {

  public CardLayout {
    sections = sections == null ? List.of() : List.copyOf(sections);
    options = options == null ? CardOptions.DEFAULT : options;
  }

  /** An empty layout: a card with only its fixed header and footer. */
  public static final CardLayout EMPTY = new CardLayout(List.of(), CardOptions.DEFAULT);

  public static CardLayout of(CardOptions options, CardSection... sections) {
    return new CardLayout(Arrays.asList(sections), options);
  }

  /** Total number of fields across every section and row. */
  public int fieldCount() {
    int n = 0;
    for (CardSection s : sections) {
      for (CardRow r : s.rows()) {
        n += r.fields().size();
      }
    }
    return n;
  }
}
