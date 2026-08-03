package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateInvoiceSeriesRequest {
  /** Last invoice number from previous software (sets prefix/pad/seed). */
  private String lastInvoiceNo;

  /** When true, reset to StockKart INV- defaults (only while unlocked). */
  private Boolean useStockKartDefault;
}
