package com.inventory.product.rest.dto.request;

import lombok.Data;

/**
 * Body for {@code POST /api/v1/cafe/tabs/{tabId}/lines}.
 *
 * <p>Omitting {@code lineRef} adds a new line for {@code sellableRef}; supplying an existing
 * line's {@code lineRef} updates its quantity and/or note instead — the frozen department never
 * changes. This is the contract the frontend was built against.
 */
@Data
public class CafeTabLineRequest {

  private String lineRef;
  private String sellableRef;
  private Integer quantity;
  private String note;
}
