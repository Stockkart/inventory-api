package com.inventory.product.labels;

/**
 * Pure geometry for sheet layouts (Req 10.3). Resolves a {@link SheetPreset} and {@link
 * StickerSizeSpec} into a concrete {@link SheetSpec} and answers compatibility questions.
 *
 * <p>For plain presets the grid is derived from the sticker size with a fixed gutter; for die-cut
 * presets the fixed table geometry is copied. Columns and rows below {@code 1} are clamped to
 * {@code 0} so an impossible combination yields {@code perSheet == 0} and is rejected by the
 * validator.
 */
public final class SheetLayoutCalculator {

  /** Gutter between stickers on a plain sheet, in millimetres. */
  static final double PLAIN_GUTTER_MM = 2;

  private SheetLayoutCalculator() {}

  /**
   * Resolves the grid for a preset and sticker size.
   *
   * <ul>
   *   <li>Plain preset: {@code columns = floor((pageWidth - 2*marginLeft + g) / (width + g))},
   *       {@code rows = floor((pageHeight - 2*marginTop + g) / (height + g))}, pitch = sticker
   *       dimension + gutter {@code g}.
   *   <li>Die-cut preset: columns, rows and pitch copied from the preset.
   * </ul>
   */
  public static SheetSpec resolve(SheetPreset preset, StickerSizeSpec size) {
    int columns;
    int rows;
    double pitchX;
    double pitchY;

    if (preset.plain()) {
      double g = PLAIN_GUTTER_MM;
      columns =
          (int)
              Math.floor(
                  (preset.pageWidthMm() - 2 * preset.marginLeftMm() + g) / (size.widthMm() + g));
      rows =
          (int)
              Math.floor(
                  (preset.pageHeightMm() - 2 * preset.marginTopMm() + g) / (size.heightMm() + g));
      pitchX = size.widthMm() + g;
      pitchY = size.heightMm() + g;
    } else {
      columns = preset.columns();
      rows = preset.rows();
      pitchX = preset.pitchXMm();
      pitchY = preset.pitchYMm();
    }

    if (columns < 1) {
      columns = 0;
    }
    if (rows < 1) {
      rows = 0;
    }

    return new SheetSpec(
        preset.id(),
        preset.pageWidthMm(),
        preset.pageHeightMm(),
        preset.marginTopMm(),
        preset.marginLeftMm(),
        pitchX,
        pitchY,
        columns,
        rows,
        columns * rows);
  }

  /**
   * Whether a preset accepts a sticker size: plain presets accept every preset sticker size;
   * die-cut presets accept exactly their {@link SheetPreset#compatibleStickerSizes()}.
   */
  public static boolean isCompatible(SheetPreset preset, String stickerSize) {
    if (preset.plain()) {
      return LabelLayoutDefaults.stickerSize(stickerSize).isPresent();
    }
    return preset.compatibleStickerSizes().contains(stickerSize);
  }
}
