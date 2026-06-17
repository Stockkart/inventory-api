package com.inventory.pluginengine.schema;

import java.util.List;
import lombok.Data;

/**
 * Inventory search contract stored on {@code entities.inventory.search} in vertical schema JSON.
 */
@Data
public class VerticalEntitySearchConfig {

  private List<VerticalSearchSortField> defaultSort;

  /**
   * Pagination strategy: {@link com.inventory.pluginengine.defaultprovider.InventorySearchCursorMode}
   * schema value (e.g. {@code compound-key}, {@code skip}).
   */
  private String cursor;
}
