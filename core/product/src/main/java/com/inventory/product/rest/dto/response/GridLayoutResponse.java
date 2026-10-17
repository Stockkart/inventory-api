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
 * { "gridId": "product-entry", "isDefault": false, "updatedAt": "…", "updatedByUserId": "u_1",
 *   "columns": [ { "key": "rowNo", "widthPx": 48, "hidden": false }, ... ],
 *   "catalog": [ { "key": "rowNo", "mandatory": true, "movable": false,
 *                  "defaultWidthPx": 48, "minWidthPx": 48, "maxWidthPx": 600 }, ... ] }
 * }</pre>
 *
 * @param columns every catalog column, in display order, mandatory ones never hidden
 * @param isDefault true when nothing is saved and the default layout is being served
 */
public record GridLayoutResponse(
    String gridId,
    boolean isDefault,
    Instant updatedAt,
    String updatedByUserId,
    List<GridColumnState> columns,
    List<GridColumnDefinition> catalog) {

  public GridLayoutResponse {
    columns = columns == null ? List.of() : List.copyOf(columns);
    catalog = catalog == null ? List.of() : List.copyOf(catalog);
  }
}
