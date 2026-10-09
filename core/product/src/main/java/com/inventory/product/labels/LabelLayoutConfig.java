package com.inventory.product.labels;

import java.util.List;
import java.util.Map;

/**
 * Normalized per-shop Label_Layout_Config: the output of validation and the content persisted in
 * {@code shop_barcode_label_layouts}. All options are non-null here; omitted request options are
 * filled from the Default_Layout before this record is built (Req 3.9).
 *
 * @param enabledFieldKeys ordered field keys to print
 * @param stickerSize preset id ({@code 50x25|38x25|100x50}) (Req 3.1)
 * @param showBarcodeText print the code as text under the bars (Req 3.2)
 * @param showFieldLabels prefix each value with its label (Req 3.3)
 * @param blankValueBehavior how blank values are rendered (Req 3.4)
 * @param printMedia {@code ROLL} or {@code SHEET} (Req 10.1); never {@code null} (defaults to
 *     {@code ROLL})
 * @param sheetPreset sheet preset id when {@code printMedia == SHEET}; {@code null} for {@code
 *     ROLL} (Req 10.1)
 * @param template sticker template (Req 11); never {@code null} (defaults to {@code STACKED})
 * @param barcodePosition bars/code placement for {@code COMPACT} (Req 11); never {@code null}
 *     (defaults to {@code TOP})
 * @param currencyStyle currency rendering style (Req 11); never {@code null} (defaults to {@code
 *     RUPEE_SYMBOL})
 * @param fieldZones field key → {@link LabelZone} name; only used when {@code template == COMPACT},
 *     a missing key means {@link LabelZone#LEFT} (Req 11); never {@code null}
 * @param fieldLabelOverrides field key → forced label on/off; an absent key means the automatic
 *     rule applies (Req 11); never {@code null}
 * @param rollSetup labels across the roll and the column gap when {@code printMedia == ROLL};
 *     {@code null} for {@code SHEET} and for roll layouts saved without a setup (legacy
 *     single-column behaviour)
 */
public record LabelLayoutConfig(
    List<String> enabledFieldKeys,
    String stickerSize,
    boolean showBarcodeText,
    boolean showFieldLabels,
    BlankValueBehavior blankValueBehavior,
    PrintMedia printMedia,
    String sheetPreset,
    StickerTemplate template,
    BarcodePosition barcodePosition,
    CurrencyStyle currencyStyle,
    Map<String, String> fieldZones,
    Map<String, Boolean> fieldLabelOverrides,
    RollSetup rollSetup) {

  /** Convenience constructor for the pre-roll-setup shape: no roll setup. */
  public LabelLayoutConfig(
      List<String> enabledFieldKeys,
      String stickerSize,
      boolean showBarcodeText,
      boolean showFieldLabels,
      BlankValueBehavior blankValueBehavior,
      PrintMedia printMedia,
      String sheetPreset,
      StickerTemplate template,
      BarcodePosition barcodePosition,
      CurrencyStyle currencyStyle,
      Map<String, String> fieldZones,
      Map<String, Boolean> fieldLabelOverrides) {
    this(
        enabledFieldKeys,
        stickerSize,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        printMedia,
        sheetPreset,
        template,
        barcodePosition,
        currencyStyle,
        fieldZones,
        fieldLabelOverrides,
        null);
  }

  public LabelLayoutConfig {
    enabledFieldKeys = enabledFieldKeys == null ? List.of() : List.copyOf(enabledFieldKeys);
    printMedia = printMedia == null ? PrintMedia.ROLL : printMedia;
    template = template == null ? LabelLayoutDefaults.DEFAULT_TEMPLATE : template;
    barcodePosition =
        barcodePosition == null ? LabelLayoutDefaults.DEFAULT_BARCODE_POSITION : barcodePosition;
    currencyStyle =
        currencyStyle == null ? LabelLayoutDefaults.DEFAULT_CURRENCY_STYLE : currencyStyle;
    fieldZones = fieldZones == null ? Map.of() : Map.copyOf(fieldZones);
    fieldLabelOverrides = fieldLabelOverrides == null ? Map.of() : Map.copyOf(fieldLabelOverrides);
  }

  /**
   * Convenience constructor for the pre-template shape: {@code STACKED} / {@code TOP} / {@code
   * RUPEE_SYMBOL} with no per-field zone or label overrides (Req 11).
   */
  public LabelLayoutConfig(
      List<String> enabledFieldKeys,
      String stickerSize,
      boolean showBarcodeText,
      boolean showFieldLabels,
      BlankValueBehavior blankValueBehavior,
      PrintMedia printMedia,
      String sheetPreset) {
    this(
        enabledFieldKeys,
        stickerSize,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        printMedia,
        sheetPreset,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for the pre-sheet-layout shape: {@link PrintMedia#ROLL} with no sheet
   * preset (Req 10.5) and template defaults (Req 11).
   */
  public LabelLayoutConfig(
      List<String> enabledFieldKeys,
      String stickerSize,
      boolean showBarcodeText,
      boolean showFieldLabels,
      BlankValueBehavior blankValueBehavior) {
    this(
        enabledFieldKeys,
        stickerSize,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        PrintMedia.ROLL,
        null);
  }
}
