package com.inventory.product.rest.dto.inventory;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class InventoryReceiptResponse {
  String lotId;
  String barcode;
  boolean reminderCreated;
}

