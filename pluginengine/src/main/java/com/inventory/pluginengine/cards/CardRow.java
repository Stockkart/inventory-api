package com.inventory.pluginengine.cards;

import java.util.Arrays;
import java.util.List;

/**
 * One printed line of a card: one to three fields rendered inline and separated by {@code " | "}.
 *
 * @param fields the fields on the line, in display order
 */
public record CardRow(List<CardField> fields) {

  public CardRow {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public static CardRow of(CardField... fields) {
    return new CardRow(Arrays.asList(fields));
  }

  /** A single labelled, normal-weight field on its own line. */
  public static CardRow single(String fieldKey) {
    return of(CardField.of(fieldKey));
  }
}
