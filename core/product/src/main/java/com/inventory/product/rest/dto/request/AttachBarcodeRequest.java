package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachBarcodeRequest {
  /** Catalog product to assign this barcode to (in-place update). */
  private String productId;
}
