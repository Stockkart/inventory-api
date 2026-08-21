package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of converting an estimate into a new SALE cart. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConvertEstimateResponse {
  /** Source estimate (now CONVERTED). */
  private String estimateId;
  private String estimateNo;
  /** New SALE quotation ready for Process Payment. */
  private String salePurchaseId;
}
