package com.inventory.product.gridlayout;

import com.inventory.pluginengine.schema.VerticalSchema;
import java.util.List;

/**
 * The columns one configurable grid can show. A screen that wants a shop-shared column layout
 * adds a Spring bean implementing this, plus its id in {@link GridIds}; the layout API, storage,
 * concurrency and permissions then work for it with no other change.
 */
public interface GridCatalog {

  /** The grid this catalog describes; a {@link GridIds} constant. */
  String gridId();

  /**
   * Every column the grid can show, in default order. A superset: the frontend draws only the
   * columns its current mode has.
   *
   * @param schema the shop's vertical schema, or null when the shop has none
   */
  List<GridColumnDefinition> columns(VerticalSchema schema);
}
