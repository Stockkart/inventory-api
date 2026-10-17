package com.inventory.product.gridlayout;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Every column a grid can show, in default order, with its layout rules and width limits.
 *
 * <p>The product entry catalog is a superset: it lists every column any billing mode or pricing
 * feature can show. The frontend renders only the columns the current mode has and uses this
 * catalog for their order, width and whether they may be hidden. Vertical schema fields appear as
 * {@code field:<schemaKey>} and follow the same registration-visibility rule the frontend uses
 * ({@code getDynamicInventoryFields(..., 'registration')}), so every vertical gets its own fields
 * without vertical checks here. A required schema field, and each core field stock-in cannot save
 * without, is {@code required} and therefore not hideable.
 */
@Component
public class GridColumnCatalog {

  public static final String ROW_NO = "rowNo";
  public static final String ACTIONS = "actions";
  public static final String FIELD_PREFIX = "field:";

  static final int MIN_WIDTH_PX = 48;
  static final int MAX_WIDTH_PX = 600;

  private static final String COMPANY_FIELD = "companyName";
  private static final String SELL_DIRECT_FIELD = "sellDirect";
  /** Rendered inline under Item Type, never as its own column. */
  private static final String DEGREE_FIELD = "itemTypeDegree";

  private static final Set<String> DYNAMIC_CORE_KEYS =
      Set.of(COMPANY_FIELD, "itemType", DEGREE_FIELD);

  /** Columns for {@code gridId}, in default order. */
  public List<GridColumnDefinition> columnsFor(String gridId, VerticalSchema schema) {
    if (!GridIds.PRODUCT_ENTRY.equals(gridId)) {
      throw new ValidationException(
          "Unknown grid: " + gridId + " (available: " + GridIds.PRODUCT_ENTRY + ")");
    }
    return productEntry(registrationFields(schema));
  }

  private static List<GridColumnDefinition> productEntry(List<VerticalSchemaField> fields) {
    List<GridColumnDefinition> out = new ArrayList<>();
    out.add(pinned(ROW_NO, 48, GridColumnPosition.FIRST));
    out.add(optional("barcode", 200));
    fields.stream()
        .filter(f -> COMPANY_FIELD.equals(f.getKey()))
        .findFirst()
        .ifPresent(f -> out.add(schemaColumn(f)));
    out.add(required("product", 240));
    fields.stream()
        .filter(f -> !COMPANY_FIELD.equals(f.getKey()) && !SELL_DIRECT_FIELD.equals(f.getKey()))
        .forEach(f -> out.add(schemaColumn(f)));
    out.add(required("qty", 80));
    out.add(optional("packaging", 210));
    out.add(required("location", 140));
    fields.stream()
        .filter(f -> SELL_DIRECT_FIELD.equals(f.getKey()))
        .findFirst()
        .ifPresent(f -> out.add(schemaColumn(f)));
    out.add(required("rate", 110));
    out.add(required("sellPrice", 110));
    out.add(required("pts", 100));
    out.add(required("ptr", 100));
    out.add(required("mrp", 100));
    out.add(optional("saleDealType", 130));
    out.add(optional("saleScheme", 110));
    out.add(optional("saleDisc", 100));
    out.add(optional("purchaseDealType", 140));
    out.add(optional("purchaseScheme", 120));
    out.add(optional("purchaseDisc", 110));
    out.add(optional("discApplicability", 130));
    out.add(optional("hsn", 110));
    out.add(optional("gstRate", 110));
    out.add(optional("cgst", 90));
    out.add(optional("sgst", 90));
    out.add(pinned(ACTIONS, 140, GridColumnPosition.LAST));
    return List.copyOf(out);
  }

  /** Inventory fields the registration grid shows as columns, in schema order. */
  private static List<VerticalSchemaField> registrationFields(VerticalSchema schema) {
    if (schema == null || schema.getEntities() == null) {
      return List.of();
    }
    VerticalEntitySchema inventory = schema.getEntities().get("inventory");
    if (inventory == null || inventory.getFields() == null) {
      return List.of();
    }
    return inventory.getFields().stream()
        .filter(f -> f.getKey() != null && !DEGREE_FIELD.equals(f.getKey()))
        .filter(f -> DYNAMIC_CORE_KEYS.contains(f.getKey()) || "extension".equals(f.getStorage()))
        .filter(GridColumnCatalog::visibleOnRegistration)
        .toList();
  }

  private static boolean visibleOnRegistration(VerticalSchemaField field) {
    if (Boolean.TRUE.equals(field.getRequired())) {
      return true;
    }
    return field.getShowIn() == null
        || field.getShowIn().isEmpty()
        || field.getShowIn().contains("registration");
  }

  private static GridColumnDefinition schemaColumn(VerticalSchemaField field) {
    boolean required =
        Boolean.TRUE.equals(field.getRequired()) || SELL_DIRECT_FIELD.equals(field.getKey());
    int width = "date".equals(field.getType()) ? 130 : COMPANY_FIELD.equals(field.getKey()) ? 160 : 140;
    return required
        ? required(FIELD_PREFIX + field.getKey(), width)
        : optional(FIELD_PREFIX + field.getKey(), width);
  }

  /** Structural: never hidden or moved. */
  private static GridColumnDefinition pinned(String key, int width, GridColumnPosition position) {
    return new GridColumnDefinition(
        key, false, false, false, position, width, MIN_WIDTH_PX, MAX_WIDTH_PX);
  }

  /** Must be filled in to save, so it is never hidden; it may move. */
  private static GridColumnDefinition required(String key, int width) {
    return new GridColumnDefinition(
        key, true, false, true, GridColumnPosition.ANY, width, MIN_WIDTH_PX, MAX_WIDTH_PX);
  }

  private static GridColumnDefinition optional(String key, int width) {
    return new GridColumnDefinition(
        key, false, true, true, GridColumnPosition.ANY, width, MIN_WIDTH_PX, MAX_WIDTH_PX);
  }
}
