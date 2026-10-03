package com.inventory.product.labels;

import java.util.List;

/**
 * A fixed sheet definition for {@link PrintMedia#SHEET} printing (Req 10.2).
 *
 * <p>Two flavours:
 *
 * <ul>
 *   <li><b>Plain</b> ({@code plain == true}): a blank page (e.g. {@code A4_PLAIN}). The grid is
 *       derived from the chosen {@link StickerSizeSpec} by {@link SheetLayoutCalculator} using a
 *       fixed gutter, so {@link #pitchXMm}, {@link #pitchYMm}, {@link #columns} and {@link #rows}
 *       are {@code 0} and {@link #compatibleStickerSizes} is empty (every preset size is accepted).
 *   <li><b>Die-cut</b> ({@code plain == false}): a pre-cut label sheet (e.g. {@code A4_65UP}). The
 *       grid geometry is fixed in the table and copied verbatim; only {@link
 *       #compatibleStickerSizes} sticker sizes are accepted.
 * </ul>
 *
 * @param id stable preset id, e.g. {@code A4_PLAIN}, {@code A4_65UP}
 * @param label human-readable label for the configuration screen
 * @param pageWidthMm page width in millimetres
 * @param pageHeightMm page height in millimetres
 * @param marginTopMm top margin in millimetres
 * @param marginLeftMm left margin in millimetres
 * @param pitchXMm horizontal cell pitch in millimetres ({@code 0} for plain presets)
 * @param pitchYMm vertical cell pitch in millimetres ({@code 0} for plain presets)
 * @param columns grid columns ({@code 0} for plain presets)
 * @param rows grid rows ({@code 0} for plain presets)
 * @param compatibleStickerSizes sticker size ids this die-cut preset supports; empty for plain
 *     presets, which accept any preset size
 * @param plain whether the grid is derived per sticker size ({@code true}) or fixed ({@code false})
 */
public record SheetPreset(
    String id,
    String label,
    double pageWidthMm,
    double pageHeightMm,
    double marginTopMm,
    double marginLeftMm,
    double pitchXMm,
    double pitchYMm,
    int columns,
    int rows,
    List<String> compatibleStickerSizes,
    boolean plain) {

  public SheetPreset {
    compatibleStickerSizes =
        compatibleStickerSizes == null ? List.of() : List.copyOf(compatibleStickerSizes);
  }
}
