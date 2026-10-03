package com.inventory.product.labels;

import java.util.List;

/**
 * The layout actually applied to a labels request: the saved (or default) config with every field
 * removed that is absent from the Field_Catalog or unavailable for the shop's type, relative order
 * preserved (Req 4.5, 6.11). Carries labels and value types per field so the renderer and the
 * server never disagree.
 *
 * @param enabledFields ordered enabled fields, enriched with label, value type, zone and label flag
 * @param stickerSize preset id
 * @param stickerSizeSpec the matching preset spec
 * @param printMedia {@code ROLL} or {@code SHEET} (Req 10.6); never {@code null}
 * @param sheetPreset sheet preset id when {@code printMedia == SHEET}; {@code null} otherwise
 * @param sheetSpec resolved sheet geometry when {@code printMedia == SHEET}; {@code null} for
 *     {@code ROLL} (Req 10.6)
 * @param template sticker template (Req 11); never {@code null} (defaults to {@code STACKED})
 * @param barcodePosition bars/code placement for {@code COMPACT} (Req 11); never {@code null}
 *     (defaults to {@code TOP})
 * @param currencyStyle currency rendering style (Req 11); never {@code null} (defaults to {@code
 *     RUPEE_SYMBOL})
 */
public record EffectiveLayout(
    List<EnabledFieldDto> enabledFields,
    String stickerSize,
    StickerSizeSpec stickerSizeSpec,
    boolean showBarcodeText,
    boolean showFieldLabels,
    BlankValueBehavior blankValueBehavior,
    PrintMedia printMedia,
    String sheetPreset,
    SheetSpec sheetSpec,
    StickerTemplate template,
    BarcodePosition barcodePosition,
    CurrencyStyle currencyStyle) {

  public EffectiveLayout {
    enabledFields = enabledFields == null ? List.of() : List.copyOf(enabledFields);
    printMedia = printMedia == null ? PrintMedia.ROLL : printMedia;
    template = template == null ? LabelLayoutDefaults.DEFAULT_TEMPLATE : template;
    barcodePosition =
        barcodePosition == null ? LabelLayoutDefaults.DEFAULT_BARCODE_POSITION : barcodePosition;
    currencyStyle =
        currencyStyle == null ? LabelLayoutDefaults.DEFAULT_CURRENCY_STYLE : currencyStyle;
  }

  /**
   * Convenience constructor for the pre-template shape: template defaults ({@code STACKED} / {@code
   * TOP} / {@code RUPEE_SYMBOL}).
   */
  public EffectiveLayout(
      List<EnabledFieldDto> enabledFields,
      String stickerSize,
      StickerSizeSpec stickerSizeSpec,
      boolean showBarcodeText,
      boolean showFieldLabels,
      BlankValueBehavior blankValueBehavior,
      PrintMedia printMedia,
      String sheetPreset,
      SheetSpec sheetSpec) {
    this(
        enabledFields,
        stickerSize,
        stickerSizeSpec,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        printMedia,
        sheetPreset,
        sheetSpec,
        null,
        null,
        null);
  }

  /** Convenience constructor for the pre-sheet-layout shape: {@link PrintMedia#ROLL}, no sheet. */
  public EffectiveLayout(
      List<EnabledFieldDto> enabledFields,
      String stickerSize,
      StickerSizeSpec stickerSizeSpec,
      boolean showBarcodeText,
      boolean showFieldLabels,
      BlankValueBehavior blankValueBehavior) {
    this(
        enabledFields,
        stickerSize,
        stickerSizeSpec,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        PrintMedia.ROLL,
        null,
        null);
  }

  /** Ordered field keys of the enabled fields. */
  public List<String> enabledFieldKeys() {
    return enabledFields.stream().map(EnabledFieldDto::fieldKey).toList();
  }
}
