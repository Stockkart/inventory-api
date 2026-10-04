package com.inventory.product.cardlayout;

import com.inventory.pluginengine.cards.CardOptions;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.labels.Sensitivity;
import com.inventory.product.labels.SourceGroup;
import com.inventory.product.labels.ValueType;
import java.util.List;

/**
 * A {@link com.inventory.pluginengine.cards.CardLayout} applied against the Field_Catalog: unknown
 * and ineligible fields removed, empty rows and sections removed, every field enriched with what the
 * frontend needs to read and format its value (configurable-product-card Req 5.1, 5.2). This is the
 * shape serialised to the frontend; it is never persisted.
 *
 * @param sections non-empty sections in display order
 * @param options the layout's options, passed through unchanged
 */
public record ResolvedCardLayout(List<Section> sections, CardOptions options) {

  public ResolvedCardLayout {
    sections = sections == null ? List.of() : List.copyOf(sections);
    options = options == null ? CardOptions.DEFAULT : options;
  }

  /** A section with at least one row. */
  public record Section(String id, String title, boolean dividerAbove, List<Row> rows) {
    public Section {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  /** A row with at least one field. */
  public record Row(List<Field> fields) {
    public Row {
      fields = fields == null ? List.of() : List.copyOf(fields);
    }
  }

  /**
   * One field with its catalog enrichment.
   *
   * @param fieldKey catalog key
   * @param label the label to print: the override when present, else the catalog label
   * @param showLabel whether to print the label
   * @param emphasis visual weight
   * @param valueType how to format the value
   * @param sourceGroup where the value comes from
   * @param itemPath dot path into the inventory summary DTO
   * @param schemaApiKey for vertical fields, the schema api key; else {@code null}
   * @param sensitivity whether the value is safe over the counter
   */
  public record Field(
      String fieldKey,
      String label,
      boolean showLabel,
      Emphasis emphasis,
      ValueType valueType,
      SourceGroup sourceGroup,
      String itemPath,
      String schemaApiKey,
      Sensitivity sensitivity) {}

  /** Total number of fields across all rows. */
  public int fieldCount() {
    int n = 0;
    for (Section s : sections) {
      for (Row r : s.rows()) {
        n += r.fields().size();
      }
    }
    return n;
  }
}
