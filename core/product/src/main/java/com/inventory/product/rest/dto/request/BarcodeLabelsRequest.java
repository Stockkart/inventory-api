package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /barcodes/labels}. Any combination of {@code productIds} and {@code codes}
 * is accepted; {@code inventoryIds} (code → inventoryId) is optional and pins the lot whose values
 * are printed for that code (Req 6.5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BarcodeLabelsRequest {
  private List<String> productIds;
  private List<String> codes;
  private Map<String, String> inventoryIds;

  public BarcodeLabelsRequest(List<String> productIds, List<String> codes) {
    this(productIds, codes, null);
  }
}
