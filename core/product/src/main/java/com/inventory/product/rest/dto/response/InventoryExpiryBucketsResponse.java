package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryExpiryBucketsResponse {
  private int expired;
  private int expiringWithin7Days;
  private int expiringWithinSoonDays;
  private int expiringSoonTotal;
  private int totalWithExpiry;
  private int expiringSoonDays;
}
