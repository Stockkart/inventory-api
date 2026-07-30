package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateBarcodesRequest {
  /** Number of barcodes to generate (1–500). Defaults to 1. */
  private Integer count;
  /** Optional batch label stored on pool rows. */
  private String batchId;
}
