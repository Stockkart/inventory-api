package com.inventory.product.rest.dto.response;

import com.inventory.product.gridlayout.GridColumnDefinition;
import com.inventory.product.gridlayout.GridColumnState;
import java.time.Instant;
import java.util.List;

/**
 * One grid's resolved column layout plus the catalog it was resolved against.
 *
 * <p>JSON shape:
 *
 * <pre>{@code
 * { "gridId": "product-entry", "isDefault": false, "revision": 12, "canEdit": true,
 *   "updatedAt": "…", "updatedByUserId": "u_1",
 *   "columns": [ { "key": "rowNo", "widthPx": 48, "hidden": false }, ... ],
 *   "catalog": [ { "key": "rowNo", "label": "#", "required": false, "hideable": false, "reorderable": false,
 *                  "position": "FIRST", "defaultWidthPx": 48, "minWidthPx": 48,
 *                  "maxWidthPx": 600 }, ... ] }
 * }</pre>
 *
 * @param isDefault true when nothing is saved and the default layout is being served
 * @param revision the saved document's revision; null while on the default
 * @param canEdit whether the caller may change the layout (owner or admin)
 * @param columns every catalog column, in display order; non-hideable ones never hidden
 */
public record GridLayoutResponse(
    String gridId,
    boolean isDefault,
    Long revision,
    boolean canEdit,
    Instant updatedAt,
    String updatedByUserId,
    List<GridColumnState> columns,
    List<GridColumnDefinition> catalog) {

  public GridLayoutResponse {
    columns = columns == null ? List.of() : List.copyOf(columns);
    catalog = catalog == null ? List.of() : List.copyOf(catalog);
  }
}
