package com.inventory.product.rest.dto.response;

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

