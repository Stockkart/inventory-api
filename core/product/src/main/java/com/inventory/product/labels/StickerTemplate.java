package com.inventory.product.labels;

/**
 * The arrangement of text on a sticker (Req 11).
 *
 * <ul>
 *   <li>{@link #STACKED}: today's single centred column of lines beneath the bars.
 *   <li>{@link #COMPACT}: a full-width shop header line, then two text columns side by side, with
 *       the bars and code placed either above or below the columns.
 * </ul>
 *
 * <p>Serialized as the enum name ({@code "STACKED"}/{@code "COMPACT"}) to match the frontend {@code
 * StickerTemplate} type.
 */
public enum StickerTemplate {
  STACKED,
  COMPACT
}
