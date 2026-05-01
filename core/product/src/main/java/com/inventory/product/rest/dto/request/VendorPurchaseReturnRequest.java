package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnRequest {

  private String vendorPurchaseInvoiceId;
  private List<Item> items;
  private String reason;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Item {
    private String inventoryId;
    /** Base units being returned — must not exceed inventory current stock. */
    private Integer baseQuantityReturned;
  }
}
