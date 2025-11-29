package com.inventory.product.rest.dto.inventory;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class InventorySummaryDto {
  String lotId;
  String barcode;
  String name;
  String description;
  String companyName;
  Integer receivedCount;
  Integer soldCount;
  Integer currentCount;
  Instant expiryDate;
  String shopId;
}

