package com.inventory.product.labels;

/**
 * The placement of a printable field on a {@link StickerTemplate#COMPACT} sticker (Req 11).
 *
 * <ul>
 *   <li>{@link #HEADER}: the full-width top line.
 *   <li>{@link #LEFT}: the left column of small lines.
 *   <li>{@link #RIGHT}: the right column of larger, bold, right-aligned lines.
 * </ul>
 *
 * <p>Ignored for {@link StickerTemplate#STACKED}, where every field renders in a single column.
 * Serialized as the enum name to match the frontend {@code LabelZone} type.
 */
public enum LabelZone {
  HEADER,
  LEFT,
  RIGHT
}
