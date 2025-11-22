package com.inventory.product.rest.dto.inventory;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class InventorySummaryDto {
  String lotId;
  String productId;
  Integer receivedCount;
  Integer soldCount;
  Integer currentCount;
  Instant expiryDate;
  String shopId;
}

