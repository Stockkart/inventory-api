package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReceiptResponse {
    String id;
  String lotId;
  String barcode;
  boolean reminderCreated;
}

