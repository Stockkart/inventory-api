package com.inventory.product.labels;

/**
 * The maximum number of enabled printable fields allowed in each {@link LabelZone} of a {@link
 * StickerTemplate#COMPACT} sticker, for a given sticker size (Req 11).
 *
 * @param header maximum fields in the {@link LabelZone#HEADER} zone
 * @param left maximum fields in the {@link LabelZone#LEFT} zone
 * @param right maximum fields in the {@link LabelZone#RIGHT} zone
 */
public record ZoneCaps(int header, int left, int right) {

  /** The cap for a single zone. */
  public int capFor(LabelZone zone) {
    return switch (zone) {
      case HEADER -> header;
      case LEFT -> left;
      case RIGHT -> right;
    };
  }
}
