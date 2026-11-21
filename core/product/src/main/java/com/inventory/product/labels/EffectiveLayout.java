package com.inventory.product.labels;

import java.util.List;
import java.util.Map;

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
 * @param rollSpec resolved roll geometry when {@code printMedia == ROLL} and the shop saved a roll
 *     setup; {@code null} for {@code SHEET} and for legacy single-column rolls
 * @param fieldZones the saved field key → {@link LabelZone} name map for {@code COMPACT}; never
 *     {@code null}. Returned so an editor reloads the zones the user saved.
 * @param fieldLabelOverrides the saved field key → forced label on/off map; never {@code null}
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
    CurrencyStyle currencyStyle,
    RollSpec rollSpec,
    Map<String, String> fieldZones,
    Map<String, Boolean> fieldLabelOverrides) {

  /** Convenience constructor for the pre-zone-echo shape: no saved zone or label maps. */
  public EffectiveLayout(
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
      CurrencyStyle currencyStyle,
      RollSpec rollSpec) {
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
        template,
        barcodePosition,
        currencyStyle,
        rollSpec,
        null,
        null);
  }

  /** Convenience constructor for the pre-roll-setup shape: no roll spec. */
  public EffectiveLayout(
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
        template,
        barcodePosition,
        currencyStyle,
        null);
  }

  public EffectiveLayout {
    enabledFields = enabledFields == null ? List.of() : List.copyOf(enabledFields);
    fieldZones = fieldZones == null ? Map.of() : Map.copyOf(fieldZones);
    fieldLabelOverrides = fieldLabelOverrides == null ? Map.of() : Map.copyOf(fieldLabelOverrides);
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
