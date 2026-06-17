package com.inventory.pluginengine.schema;

import lombok.Data;

/** One sort key in {@link VerticalEntitySearchConfig#getDefaultSort()}. */
@Data
public class VerticalSearchSortField {

  private String field;
  /** {@code asc} or {@code desc}. Defaults to {@code asc}. */
  private String direction;
  /** For nullable dates: {@code last} (default) or {@code first}. */
  private String nulls;
}
