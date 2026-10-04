package com.inventory.pluginengine.cards;

/**
 * Per-layout switches that are not about individual fields.
 *
 * @param blankValueBehavior what to do with blank values
 * @param showAttributeChips render the item-type / discount / scheme chips after the sections
 * @param showDescription render the product description after the sections
 */
public record CardOptions(
    CardBlankValueBehavior blankValueBehavior, boolean showAttributeChips, boolean showDescription) {

  /** Today's behaviour: hide blank lines, show chips and description. */
  public static final CardOptions DEFAULT = new CardOptions(CardBlankValueBehavior.HIDE_LINE, true, true);

  public CardOptions {
    blankValueBehavior =
        blankValueBehavior == null ? CardBlankValueBehavior.HIDE_LINE : blankValueBehavior;
  }
}
