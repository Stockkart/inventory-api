package com.inventory.product.rest.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Response of {@code POST /api/v1/inventory/search} (advanced-product-search R4.2, R5.1).
 *
 * @param data one page of lots, same shape as every other inventory list
 * @param page exact paging information
 * @param facets field key → values with counts (only the fields the request asked for)
 * @param appliedSort the sort that was used, so the UI can show it when the shop default applied
 */
public record SearchResponse(
    List<InventorySummaryDto> data, PageMeta page, Map<String, List<FacetValue>> facets, String appliedSort) {

  public SearchResponse {
    data = data == null ? List.of() : List.copyOf(data);
    facets = facets == null ? Map.of() : Map.copyOf(facets);
  }

  /** One facet value and how many results carry it. */
  public record FacetValue(String value, String label, long count) {}
}
