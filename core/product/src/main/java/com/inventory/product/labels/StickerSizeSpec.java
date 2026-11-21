package com.inventory.product.labels;

/**
 * A sticker size preset (Req 3.1, 3.5, 11).
 *
 * @param size preset id, e.g. {@code 50x25}
 * @param widthMm sticker width in millimetres
 * @param heightMm sticker height in millimetres
 * @param maxLines maximum number of field lines that fit (the code line is not counted)
 * @param zoneCaps per-zone field caps for the {@link StickerTemplate#COMPACT} template; never
 *     {@code null}
 */
public record StickerSizeSpec(String size, int widthMm, int heightMm, int maxLines, ZoneCaps zoneCaps) {

  /**
   * Convenience constructor for the pre-template shape: derives {@link #zoneCaps} from the sticker
   * size via {@link LabelLayoutDefaults#zoneCaps(String)}.
   */
  public StickerSizeSpec(String size, int widthMm, int heightMm, int maxLines) {
    this(size, widthMm, heightMm, maxLines, LabelLayoutDefaults.zoneCaps(size));
  }
}
