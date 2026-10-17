package com.inventory.product.rest.dto.request;

import java.util.List;

/**
 * Body of {@code PUT /api/v1/shops/active-shop/grid-layouts/{gridId}}.
 *
 * <p>No {@code shopId} or {@code gridId} in the body: both come from the request context and the
 * path. Columns the body leaves out keep their default position and width.
 *
 * @param columns columns in display order
 */
public record SaveGridLayoutRequest(List<ColumnSpec> columns) {

  public SaveGridLayoutRequest {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }

  /** One column as submitted; a null {@code widthPx} means the default width. */
  public record ColumnSpec(String key, Integer widthPx, Boolean hidden) {}
}
