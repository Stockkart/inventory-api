package com.inventory.product.rest.dto.request;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code PUT /api/v1/shops/active-shop/barcode-label-layout}.
 *
 * <p>There is deliberately no {@code shopId} component: the shop always comes from the
 * authenticated request context, and any {@code shopId} in the body is ignored by Jackson
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES} is off in this application).
 *
 * <p>The two boolean options are bound to {@link LenientBoolean} rather than {@link Boolean} so
 * that non-boolean input such as {@code "yes"} or {@code 1} does not abort JSON binding; the holder
 * records the offending raw text and the validator turns it into {@code "showBarcodeText must be
 * true or false"} (see {@link LenientBoolean} for the full rules). Use {@link
 * #showBarcodeTextValue()} / {@link #showFieldLabelsValue()} for the parsed values and {@link
 * #isShowBarcodeTextInvalid()} / {@link #isShowFieldLabelsInvalid()} for the error markers.
 *
 * @param enabledFieldKeys ordered field keys to print; {@code null} is treated as empty by the
 *     validator
 * @param stickerSize preset sticker size such as {@code "50x25"}; {@code null} means "use default"
 * @param showBarcodeText optional; {@code null} means "use default"
 * @param showFieldLabels optional; {@code null} means "use default"
 * @param blankValueBehavior {@code "HIDE_LINE"} or {@code "PRINT_BLANK"}; {@code null} means "use
 *     default"
 * @param printMedia {@code "ROLL"} or {@code "SHEET"}; {@code null} means "use default" ({@code
 *     ROLL}) (Req 10.1, 10.5)
 * @param sheetPreset sheet preset id; required when {@code printMedia} is {@code SHEET}, ignored
 *     when {@code ROLL} (Req 10.1)
 * @param template {@code "STACKED"} or {@code "COMPACT"}; {@code null} means "use default" ({@code
 *     STACKED}) (Req 11)
 * @param barcodePosition {@code "TOP"} or {@code "BOTTOM"}; {@code null} means "use default" ({@code
 *     TOP}) (Req 11)
 * @param currencyStyle {@code "RUPEE_SYMBOL"} or {@code "RS_PREFIX"}; {@code null} means "use
 *     default" ({@code RUPEE_SYMBOL}) (Req 11)
 * @param fieldZones field key → {@code LabelZone} name; only used when {@code template} is {@code
 *     COMPACT}; keys not in {@code enabledFieldKeys} are dropped (Req 11)
 * @param fieldLabelOverrides field key → force label on/off; absent key means the automatic rule;
 *     keys not in {@code enabledFieldKeys} are dropped (Req 11)
 */
public record SaveLabelLayoutRequest(
    List<String> enabledFieldKeys,
    String stickerSize,
    LenientBoolean showBarcodeText,
    LenientBoolean showFieldLabels,
    String blankValueBehavior,
    String printMedia,
    String sheetPreset,
    String template,
    String barcodePosition,
    String currencyStyle,
    Map<String, String> fieldZones,
    Map<String, Boolean> fieldLabelOverrides) {

  /**
   * Convenience constructor for the pre-sheet-layout shape with {@link LenientBoolean} options and
   * no print media (defaults to {@code ROLL}).
   */
  public SaveLabelLayoutRequest(
      List<String> enabledFieldKeys,
      String stickerSize,
      LenientBoolean showBarcodeText,
      LenientBoolean showFieldLabels,
      String blankValueBehavior) {
    this(
        enabledFieldKeys,
        stickerSize,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for the pre-sheet-layout shape: plain booleans, no print media (defaults
   * to {@code ROLL}).
   */
  public SaveLabelLayoutRequest(
      List<String> enabledFieldKeys,
      String stickerSize,
      Boolean showBarcodeText,
      Boolean showFieldLabels,
      String blankValueBehavior) {
    this(
        enabledFieldKeys,
        stickerSize,
        LenientBoolean.of(showBarcodeText),
        LenientBoolean.of(showFieldLabels),
        blankValueBehavior,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Convenience constructor with print media, for callers (and tests) that hold plain booleans. */
  public SaveLabelLayoutRequest(
      List<String> enabledFieldKeys,
      String stickerSize,
      Boolean showBarcodeText,
      Boolean showFieldLabels,
      String blankValueBehavior,
      String printMedia,
      String sheetPreset) {
    this(
        enabledFieldKeys,
        stickerSize,
        LenientBoolean.of(showBarcodeText),
        LenientBoolean.of(showFieldLabels),
        blankValueBehavior,
        printMedia,
        sheetPreset,
        null,
        null,
        null,
        null,
        null);
  }

  /** Parsed {@code showBarcodeText}, or {@code null} when omitted or invalid. */
  public Boolean showBarcodeTextValue() {
    return LenientBoolean.valueOf(showBarcodeText);
  }

  /** Parsed {@code showFieldLabels}, or {@code null} when omitted or invalid. */
  public Boolean showFieldLabelsValue() {
    return LenientBoolean.valueOf(showFieldLabels);
  }

  /** True when {@code showBarcodeText} was supplied but was not a boolean. */
  public boolean isShowBarcodeTextInvalid() {
    return LenientBoolean.isInvalid(showBarcodeText);
  }

  /** True when {@code showFieldLabels} was supplied but was not a boolean. */
  public boolean isShowFieldLabelsInvalid() {
    return LenientBoolean.isInvalid(showFieldLabels);
  }
}
