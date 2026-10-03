package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.labels.BarcodePosition;
import com.inventory.product.labels.BlankValueBehavior;
import com.inventory.product.labels.CurrencyStyle;
import com.inventory.product.labels.EffectiveLayout;
import com.inventory.product.labels.EnabledFieldDto;
import com.inventory.product.labels.PrintMedia;
import com.inventory.product.labels.SheetSpec;
import com.inventory.product.labels.StickerSizeSpec;
import com.inventory.product.labels.StickerTemplate;
import java.time.Instant;
import java.util.List;

/**
 * Barcode label layout as returned by {@code GET/PUT /barcode-label-layout}, {@code GET /defaults}
 * and embedded as {@code layout} in the labels response.
 *
 * <p>JSON shape:
 *
 * <pre>{@code
 * {
 *   "enabledFields": [{ "fieldKey": "productName", "label": "Product name", "valueType": "text" }],
 *   "stickerSize": "50x25",
 *   "stickerSizeSpec": { "size": "50x25", "widthMm": 50, "heightMm": 25, "maxLines": 3 },
 *   "showBarcodeText": true,
 *   "showFieldLabels": false,
 *   "blankValueBehavior": "HIDE_LINE",
 *   "printMedia": "ROLL",
 *   "sheetPreset": null,
 *   "sheetSpec": null,
 *   "isDefault": true,
 *   "updatedAt": null,
 *   "updatedByUserId": null,
 *   "shopType": "RETAILER"
 * }
 * }</pre>
 *
 * @param isDefault true when no layout is saved for the shop (or for shop-type defaults); the JSON
 *     property name is exactly {@code isDefault}
 * @param updatedAt {@code null} when {@code isDefault} is true
 * @param updatedByUserId {@code null} when {@code isDefault} is true
 */
public record LabelLayoutResponse(
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
    @JsonProperty("isDefault") boolean isDefault,
    Instant updatedAt,
    String updatedByUserId,
    ShopType shopType) {

  public LabelLayoutResponse {
    enabledFields = enabledFields == null ? List.of() : List.copyOf(enabledFields);
    printMedia = printMedia == null ? PrintMedia.ROLL : printMedia;
    template = template == null ? StickerTemplate.STACKED : template;
    barcodePosition = barcodePosition == null ? BarcodePosition.TOP : barcodePosition;
    currencyStyle = currencyStyle == null ? CurrencyStyle.RUPEE_SYMBOL : currencyStyle;
  }

  /** Builds the response from an effective layout plus persistence metadata. */
  public static LabelLayoutResponse from(
      EffectiveLayout layout,
      boolean isDefault,
      Instant updatedAt,
      String updatedByUserId,
      ShopType shopType) {
    return new LabelLayoutResponse(
        layout.enabledFields(),
        layout.stickerSize(),
        layout.stickerSizeSpec(),
        layout.showBarcodeText(),
        layout.showFieldLabels(),
        layout.blankValueBehavior(),
        layout.printMedia(),
        layout.sheetPreset(),
        layout.sheetSpec(),
        layout.template(),
        layout.barcodePosition(),
        layout.currencyStyle(),
        isDefault,
        updatedAt,
        updatedByUserId,
        shopType);
  }

  /** The effective layout this response was built from (inverse of {@link #from}). */
  public EffectiveLayout toEffectiveLayout() {
    return new EffectiveLayout(
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
        currencyStyle);
  }
}
