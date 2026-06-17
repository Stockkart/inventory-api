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
   * Pagination strategy: {@code compound-key} (keyset on sort fields) or {@code skip} (offset token).
   */
  private String cursor;
}
