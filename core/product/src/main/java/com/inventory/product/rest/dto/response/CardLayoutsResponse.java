package com.inventory.product.rest.dto.response;

import java.util.List;

/**
 * Response of {@code GET /api/v1/shops/active-shop/card-layouts}: every surface available to the
 * shop, saved or default, in surface order (configurable-product-card Req 4.4).
 */
public record CardLayoutsResponse(List<SurfaceLayoutResponse> surfaces) {

  public CardLayoutsResponse {
    surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
  }
}
