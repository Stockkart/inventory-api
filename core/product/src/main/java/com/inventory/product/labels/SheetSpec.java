package com.inventory.product.labels;

/**
 * The resolved sheet geometry for one {@link SheetPreset} and {@link StickerSizeSpec} pair (Req
 * 10.3, 10.6): the page size, margins, cell pitch and the computed grid. Returned in the effective
 * layout when {@link PrintMedia#SHEET} is in effect.
 *
 * @param presetId the {@link SheetPreset#id()} this spec resolves
 * @param pageWidthMm page width in millimetres
 * @param pageHeightMm page height in millimetres
 * @param marginTopMm top margin in millimetres
 * @param marginLeftMm left margin in millimetres
 * @param pitchXMm horizontal cell pitch in millimetres
 * @param pitchYMm vertical cell pitch in millimetres
 * @param columns grid columns (clamped to {@code 0} when the sticker cannot fit)
 * @param rows grid rows (clamped to {@code 0} when the sticker cannot fit)
 * @param perSheet stickers per sheet, {@code columns * rows}
 */
public record SheetSpec(
    String presetId,
    double pageWidthMm,
    double pageHeightMm,
    double marginTopMm,
    double marginLeftMm,
    double pitchXMm,
    double pitchYMm,
    int columns,
    int rows,
    int perSheet) {}
