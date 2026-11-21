package com.inventory.pluginengine.cards;

/**
 * A reference to one catalog field inside a card row, with its presentation attributes.
 *
 * @param fieldKey catalog {@code fieldKey}; the only thing persisted that ties a card to data
 * @param showLabel whether to prefix the value with its label and a colon
 * @param labelOverride replaces the catalog label when non-blank; {@code null} means use the
 *     catalog label
 * @param emphasis visual weight; never {@code null}
 */
public record CardField(String fieldKey, boolean showLabel, String labelOverride, Emphasis emphasis) {

  public CardField {
    emphasis = emphasis == null ? Emphasis.NORMAL : emphasis;
    labelOverride = labelOverride == null || labelOverride.isBlank() ? null : labelOverride.trim();
  }

  /** A labelled, normal-weight field — the common case. */
  public static CardField of(String fieldKey) {
    return new CardField(fieldKey, true, null, Emphasis.NORMAL);
  }

  /** A labelled field with a custom label. */
  public static CardField labelled(String fieldKey, String label) {
    return new CardField(fieldKey, true, label, Emphasis.NORMAL);
  }

  /** A labelled, bold field. */
  public static CardField strong(String fieldKey) {
    return new CardField(fieldKey, true, null, Emphasis.STRONG);
  }

  /** A labelled, bold field with a custom label. */
  public static CardField strong(String fieldKey, String label) {
    return new CardField(fieldKey, true, label, Emphasis.STRONG);
  }
}
