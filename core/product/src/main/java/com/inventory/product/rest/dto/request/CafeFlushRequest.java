package com.inventory.product.rest.dto.request;

import lombok.Data;

/**
 * Body for {@code POST /api/v1/cafe/tabs/{tabId}/flush}. {@code purchaseId} null asks the server
 * to open a new bill.
 */
@Data
public class CafeFlushRequest {

  private String purchaseId;
}
