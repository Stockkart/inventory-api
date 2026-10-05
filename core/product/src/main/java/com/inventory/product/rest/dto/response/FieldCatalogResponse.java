package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.LabelLayoutDefaults;
import com.inventory.product.labels.StickerSizeSpec;
import java.util.List;

/**
 * Response of {@code GET /api/v1/shops/active-shop/barcode-label-layout/field-catalog}.
 *
 * <p>JSON shape:
 *
 * <pre>{@code
 * {
 *   "fields": [{ "fieldKey": "mrp", "label": "MRP", "sourceGroup": "pricing",
 *                "valueType": "currency", "availableForShopTypes": ["RETAILER", ...],
 *                "schemaApiKey": null }],
 *   "stickerSizes": [{ "size": "50x25", "widthMm": 50, "heightMm": 25, "maxLines": 3 }],
 *   "shopType": "RETAILER",
 *   "verticalSchemaLoaded": true,
 *   "sheetPresets": [{ "id": "A4_PLAIN", "label": "A4 (plain)", "plain": true,
 *                      "compatibleStickerSizes": [],
 *                      "perStickerSize": { "50x25": { "columns": 3, "rows": 10, "perSheet": 30,
 *                                                     "pitchXMm": 52.0, "pitchYMm": 27.0 } } }]
 * }
 * }</pre>
 *
 * @param verticalSchemaLoaded false when the vertical schema could not be loaded and the vertical
 *     group is therefore empty
 */
public record FieldCatalogResponse(
    List<PrintableFieldDto> fields,
    List<StickerSizeSpec> stickerSizes,
    ShopType shopType,
    boolean verticalSchemaLoaded,
    List<SheetPresetDto> sheetPresets,
    List<TemplateDto> templates) {

  public FieldCatalogResponse {
    fields = fields == null ? List.of() : List.copyOf(fields);
    stickerSizes = stickerSizes == null ? List.of() : List.copyOf(stickerSizes);
    sheetPresets = sheetPresets == null ? List.of() : List.copyOf(sheetPresets);
    templates = templates == null ? List.of() : List.copyOf(templates);
  }

  public static FieldCatalogResponse from(FieldCatalog catalog) {
    List<SheetPresetDto> sheetPresets =
        LabelLayoutDefaults.SHEET_PRESETS.stream()
            .map(preset -> SheetPresetDto.from(preset, catalog.stickerSizes()))
            .toList();
    return new FieldCatalogResponse(
        catalog.forUsage(FieldUsage.LABEL).stream().map(PrintableFieldDto::from).toList(),
        catalog.stickerSizes(),
        catalog.effectiveShopType(),
        catalog.verticalSchemaLoaded(),
        sheetPresets,
        TemplateDto.ALL);
  }
}
