package com.inventory.product.rest.dto.response;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Map of inventoryId → most recent purchases of that line by the same customer (newest first).
 * Missing keys mean the customer has no prior purchase of that inventory line.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProductHistoryResponse {
  private String customerId;
  private int perItemLimit;
  private Map<String, List<CustomerProductHistoryEntry>> history;
}
