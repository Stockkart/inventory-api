package com.inventory.product.labels;

/**
 * Pure geometry for roll layouts. Resolves a {@link RollSetup} and {@link StickerSizeSpec} into a
 * concrete {@link RollSpec} and holds the bounds the validator enforces.
 */
public final class RollLayoutCalculator {

  /** Fewest labels per row. */
  public static final int MIN_LABELS_ACROSS = 1;

  /** Most labels per row; wider webs than this do not fit desktop label printers. */
  public static final int MAX_LABELS_ACROSS = 4;

  /** Widest column gap accepted, in millimetres. */
  public static final double MAX_COLUMN_GAP_MM = 20;

  /** Dot pitch of a 203 dpi thermal label printer, in millimetres. */
  public static final double DOT_MM = 25.4 / 203;

  private RollLayoutCalculator() {}

  /**
   * Resolves the page box for one row: width {@code across * w + (across - 1) * gap}, height
   * {@code h}, and the column pitch {@code w + gap} snapped to whole printer dots. Inputs are
   * assumed valid (see {@link #isValidLabelsAcross} / {@link #isValidColumnGap}).
   */
  public static RollSpec resolve(RollSetup setup, StickerSizeSpec size) {
    int across = setup.labelsAcross();
    double gap = setup.columnGapMm();
    double pageWidth = across * size.widthMm() + (across - 1) * gap;
    return new RollSpec(across, gap, pageWidth, size.heightMm(), snapToDots(size.widthMm() + gap));
  }

  /** Nearest whole number of 203 dpi printer dots, in millimetres, to four decimals. */
  static double snapToDots(double mm) {
    double snapped = Math.round(mm / DOT_MM) * DOT_MM;
    return Math.round(snapped * 10_000) / 10_000.0;
  }

  /** Whether {@code across} lies in {@code [MIN_LABELS_ACROSS, MAX_LABELS_ACROSS]}. */
  public static boolean isValidLabelsAcross(int across) {
    return across >= MIN_LABELS_ACROSS && across <= MAX_LABELS_ACROSS;
  }

  /** Whether {@code gapMm} is finite and lies in {@code [0, MAX_COLUMN_GAP_MM]}. */
  public static boolean isValidColumnGap(double gapMm) {
    return Double.isFinite(gapMm) && gapMm >= 0 && gapMm <= MAX_COLUMN_GAP_MM;
  }
}
