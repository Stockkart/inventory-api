package com.inventory.product.rest.dto.request;

import java.util.List;

/**
 * Body of {@code POST /api/v1/inventory/search} (advanced-product-search R2.1, R2.2).
 *
 * <p>Everything is optional. Enum-like values are strings here so a wrong value becomes a named
 * validation message from {@code SearchRequestValidator} rather than a bare JSON parse failure.
 *
 * @param text the search text; may be empty
 * @param textMode {@code pattern} (default) or {@code regex}
 * @param filters filter groups; values inside a group are OR
 * @param match {@code all} (default) or {@code any} — how groups combine
 * @param facets field keys to return value counts for; empty means none
 * @param sort {@code fieldKey:asc|desc}; {@code null} means the shop default
 * @param page zero-based page
 * @param size page size, capped at 200
 * @param includeZeroStock include sold-out lots; default true
 * @param surface who is asking ({@code product-search}, {@code scan-sell}); metrics only
 */
public record SearchRequest(
    String text,
    String textMode,
    List<FilterGroup> filters,
    String match,
    List<String> facets,
    String sort,
    Integer page,
    Integer size,
    Boolean includeZeroStock,
    String surface) {

  public SearchRequest {
    filters = filters == null ? List.of() : List.copyOf(filters);
    facets = facets == null ? List.of() : List.copyOf(facets);
  }

  /**
   * One filter group.
   *
   * @param field catalog field key
   * @param op operator wire name ({@code in}, {@code matches}, {@code between}, {@code withinDays},
   *     {@code exists})
   * @param values the values ({@code in}: the list; {@code matches}: one pattern; {@code withinDays}:
   *     one number of days)
   * @param from lower bound for {@code between} (number or ISO date); optional
   * @param to upper bound for {@code between}; optional
   */
  public record FilterGroup(String field, String op, List<String> values, String from, String to) {
    public FilterGroup {
      values = values == null ? List.of() : List.copyOf(values);
    }
  }
}
