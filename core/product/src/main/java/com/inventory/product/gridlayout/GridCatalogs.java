package com.inventory.product.gridlayout;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalSchema;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Every registered {@link GridCatalog}, looked up by grid id. */
@Component
public class GridCatalogs {

  private final Map<String, GridCatalog> byGridId = new TreeMap<>();

  public GridCatalogs(List<GridCatalog> catalogs) {
    for (GridCatalog catalog : catalogs) {
      if (byGridId.putIfAbsent(catalog.gridId(), catalog) != null) {
        throw new IllegalStateException("Two grid catalogs for grid " + catalog.gridId());
      }
    }
  }

  /**
   * Columns for {@code gridId}, in default order.
   *
   * @throws ValidationException for a grid with no catalog
   */
  public List<GridColumnDefinition> columnsFor(String gridId, VerticalSchema schema) {
    GridCatalog catalog = byGridId.get(gridId);
    if (catalog == null) {
      throw new ValidationException(
          "Unknown grid: " + gridId + " (available: " + String.join(", ", byGridId.keySet()) + ")");
    }
    return catalog.columns(schema);
  }
}
