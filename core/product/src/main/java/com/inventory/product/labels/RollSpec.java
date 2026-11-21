package com.inventory.product.labels;

/**
 * The resolved roll geometry for one {@link RollSetup} and {@link StickerSizeSpec} pair: the labels
 * per row, the column gap and the page box one row occupies. Returned in the effective layout when
 * {@link PrintMedia#ROLL} is in effect and the shop has saved a roll setup; {@code null} otherwise.
 *
 * <p>The renderer prints one row per page: {@code pageWidthMm} is the full web width covered by the
 * labels and gaps, {@code pageHeightMm} is one label's height. The printer's gap sensor feeds the
 * next row, so no vertical gap is modelled.
 *
 * @param labelsAcross labels per row
 * @param columnGapMm horizontal gap between neighbouring labels in millimetres
 * @param pageWidthMm {@code labelsAcross * width + (labelsAcross - 1) * columnGapMm}
 * @param pageHeightMm the sticker height
 * @param pitchMm distance from one column's left edge to the next ({@code width + columnGapMm}),
 *     rounded to a whole number of 203 dpi printer dots so every column starts on the same dot
 *     phase and identical text rasterises identically in each column
 */
public record RollSpec(
    int labelsAcross,
    double columnGapMm,
    double pageWidthMm,
    double pageHeightMm,
    double pitchMm) {}
