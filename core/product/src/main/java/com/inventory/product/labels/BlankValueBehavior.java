package com.inventory.product.labels;

/**
 * What the renderer does with an enabled field whose resolved value is blank.
 *
 * <ul>
 *   <li>{@link #HIDE_LINE}: the line is omitted (legacy behaviour).
 *   <li>{@link #PRINT_BLANK}: the line is kept (label only, or empty) so line positions stay uniform
 *       across stickers.
 * </ul>
 */
public enum BlankValueBehavior {
  HIDE_LINE,
  PRINT_BLANK
}
