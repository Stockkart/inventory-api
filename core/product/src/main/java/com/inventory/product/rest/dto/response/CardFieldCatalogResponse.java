package com.inventory.product.rest.dto.response;

import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.product.cardlayout.CardLayoutDefaults;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.labels.Sensitivity;
import com.inventory.product.labels.SourceGroup;
import com.inventory.product.labels.ValueType;
import java.util.List;
import java.util.TreeSet;

/**
 * Response of {@code GET /api/v1/shops/active-shop/card-layouts/field-catalog}
 * (configurable-product-card Req 1.5).
 *
 * <pre>{@code
 * { "fields": [{ "fieldKey": "mrp", "label": "MRP", "sourceGroup": "pricing", "valueType": "currency",
 *                "itemPath": "maximumRetailPrice", "schemaApiKey": null, "sensitivity": "PUBLIC" }],
 *   "surfaces": [{ "surfaceId": "scan-sell", "label": "Scan & Sell results",
 *                  "billingModeAware": true, "excludedFieldKeys": ["description"] }],
 *   "limits": { "maxSections": 6, "maxRowsPerSection": 8, "maxFieldsPerRow": 3,
 *               "maxFieldsTotal": 20, "maxTextLength": 40 },
 *   "verticalSchemaLoaded": true }
 * }</pre>
 */
public record CardFieldCatalogResponse(
    List<Field> fields, List<Surface> surfaces, Limits limits, boolean verticalSchemaLoaded) {

  public CardFieldCatalogResponse {
    fields = fields == null ? List.of() : List.copyOf(fields);
    surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
    limits = limits == null ? Limits.CURRENT : limits;
  }

  public static CardFieldCatalogResponse from(FieldCatalog catalog, List<CardSurfaceDefinition> surfaces) {
    return new CardFieldCatalogResponse(
        catalog.forUsage(FieldUsage.CARD).stream().map(Field::from).toList(),
        surfaces.stream().map(Surface::from).toList(),
        Limits.CURRENT,
        catalog.verticalSchemaLoaded());
  }

  /** One card-usable catalog field. */
  public record Field(
      String fieldKey,
      String label,
      SourceGroup sourceGroup,
      ValueType valueType,
      String itemPath,
      String schemaApiKey,
      Sensitivity sensitivity) {

    static Field from(PrintableField f) {
      return new Field(
          f.fieldKey(), f.label(), f.sourceGroup(), f.valueType(), f.itemPath(), f.schemaApiKey(), f.sensitivity());
    }
  }

  /** One surface the shop may configure. */
  public record Surface(String surfaceId, String label, boolean billingModeAware, List<String> excludedFieldKeys) {

    public Surface {
      excludedFieldKeys = excludedFieldKeys == null ? List.of() : List.copyOf(excludedFieldKeys);
    }

    static Surface from(CardSurfaceDefinition d) {
      return new Surface(
          d.surfaceId(), d.label(), d.billingModeAware(), List.copyOf(new TreeSet<>(d.excludedFieldKeys())));
    }
  }

  /** Editor caps, so the frontend never has to hardcode them. */
  public record Limits(
      int maxSections, int maxRowsPerSection, int maxFieldsPerRow, int maxFieldsTotal, int maxTextLength) {

    static final Limits CURRENT =
        new Limits(
            CardLayoutDefaults.MAX_SECTIONS,
            CardLayoutDefaults.MAX_ROWS_PER_SECTION,
            CardLayoutDefaults.MAX_FIELDS_PER_ROW,
            CardLayoutDefaults.MAX_FIELDS_TOTAL,
            CardLayoutDefaults.MAX_TEXT_LENGTH);
  }
}
