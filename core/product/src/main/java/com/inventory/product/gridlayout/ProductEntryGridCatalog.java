package com.inventory.product.gridlayout;

import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Columns of the product entry (stock-in) grid, in default order.
 *
 * <p>A superset: every column any billing mode or pricing feature can show. Vertical schema
 * fields follow the same registration-visibility rule the frontend uses ({@code
 * getDynamicInventoryFields(..., 'registration')}), so every vertical gets its own fields with no
 * vertical checks here. Each core field stock-in cannot save without is required.
 */
@Component
public class ProductEntryGridCatalog implements GridCatalog {

  public static final String ROW_NO = "rowNo";
  public static final String ACTIONS = "actions";

  private static final String COMPANY_FIELD = "companyName";
  private static final String SELL_DIRECT_FIELD = "sellDirect";
  /** Rendered inline under Item Type, never as its own column. */
  private static final String DEGREE_FIELD = "itemTypeDegree";

  private static final Set<String> DYNAMIC_CORE_KEYS =
      Set.of(COMPANY_FIELD, "itemType", DEGREE_FIELD);

  @Override
  public String gridId() {
    return GridIds.PRODUCT_ENTRY;
  }

  @Override
  public List<GridColumnDefinition> columns(VerticalSchema schema) {
    List<VerticalSchemaField> fields =
        SchemaGridColumns.fields(schema, "inventory", ProductEntryGridCatalog::isGridField);
    List<GridColumnDefinition> out = new ArrayList<>();
    out.add(GridColumnDefinition.pinned(ROW_NO, "#", 48, GridColumnPosition.FIRST));
    out.add(GridColumnDefinition.optional("barcode", "Barcode", 200));
    fields.stream()
        .filter(f -> COMPANY_FIELD.equals(f.getKey()))
        .findFirst()
        .ifPresent(f -> out.add(schemaColumn(f)));
    out.add(GridColumnDefinition.required("product", "Product", 240));
    fields.stream()
        .filter(f -> !COMPANY_FIELD.equals(f.getKey()) && !SELL_DIRECT_FIELD.equals(f.getKey()))
        .forEach(f -> out.add(schemaColumn(f)));
    out.add(GridColumnDefinition.required("qty", "Qty", 80));
    out.add(GridColumnDefinition.required("packaging", "Packaging", 210));
    out.add(GridColumnDefinition.required("location", "Location", 140));
    fields.stream()
        .filter(f -> SELL_DIRECT_FIELD.equals(f.getKey()))
        .findFirst()
        .ifPresent(f -> out.add(schemaColumn(f)));
    out.add(GridColumnDefinition.required("rate", "Rate", 110));
    out.add(GridColumnDefinition.required("sellPrice", "Sell price", 110));
    out.add(GridColumnDefinition.required("pts", "PTS", 100));
    out.add(GridColumnDefinition.required("ptr", "PTR", 100));
    out.add(GridColumnDefinition.required("mrp", "MRP", 100));
    out.add(GridColumnDefinition.optional("saleDealType", "Sale deal type", 130));
    out.add(GridColumnDefinition.optional("saleScheme", "Sale scheme", 110));
    out.add(GridColumnDefinition.optional("saleDisc", "Sale disc %", 100));
    out.add(GridColumnDefinition.optional("purchaseDealType", "Purchase deal type", 140));
    out.add(GridColumnDefinition.optional("purchaseScheme", "Purchase scheme", 120));
    out.add(GridColumnDefinition.optional("purchaseDisc", "Purchase disc %", 110));
    out.add(GridColumnDefinition.optional("discApplicability", "Discount applicable", 130));
    out.add(GridColumnDefinition.optional("hsn", "HSN", 110));
    // GST is required on every taxable lot (no shop default), so its picker cannot be hidden
    out.add(GridColumnDefinition.required("gstRate", "GST rate", 110));
    out.add(GridColumnDefinition.optional("cgst", "CGST %", 90));
    out.add(GridColumnDefinition.optional("sgst", "SGST %", 90));
    out.add(GridColumnDefinition.pinned(ACTIONS, "Actions", 140, GridColumnPosition.LAST));
    return List.copyOf(out);
  }

  /** Vertical-owned inventory fields shown on registration; degree folds into Item Type. */
  private static boolean isGridField(VerticalSchemaField field) {
    if (DEGREE_FIELD.equals(field.getKey())) {
      return false;
    }
    if (!DYNAMIC_CORE_KEYS.contains(field.getKey()) && !"extension".equals(field.getStorage())) {
      return false;
    }
    return Boolean.TRUE.equals(field.getRequired())
        || field.getShowIn() == null
        || field.getShowIn().isEmpty()
        || field.getShowIn().contains("registration");
  }

  private static GridColumnDefinition schemaColumn(VerticalSchemaField field) {
    int width =
        "date".equals(field.getType()) ? 130 : COMPANY_FIELD.equals(field.getKey()) ? 160 : 140;
    // Sell-direct always has a value (default "no"), so the grid never hides it.
    return SchemaGridColumns.column(field, width, SELL_DIRECT_FIELD.equals(field.getKey()));
  }
}
