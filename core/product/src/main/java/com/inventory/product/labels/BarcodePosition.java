package com.inventory.product.labels;

/**
 * Where the bars and code sit relative to the text columns on a {@link StickerTemplate#COMPACT}
 * sticker (Req 11).
 *
 * <p>Serialized as the enum name ({@code "TOP"}/{@code "BOTTOM"}) to match the frontend {@code
 * BarcodePosition} type.
 */
public enum BarcodePosition {
  TOP,
  BOTTOM
}
