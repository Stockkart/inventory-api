package com.inventory.product.rest.dto.request;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnRequest {

  private String vendorPurchaseInvoiceId;
  private List<Item> items;
  private String reason;

  /** How the supplier refunds you: {@code CASH}, {@code ONLINE}, {@code CREDIT}, or mixed. */
  private String paymentMethod;

  private BigDecimal cashAmount;
  private BigDecimal onlineAmount;
  private BigDecimal creditAmount;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Item {
    private String inventoryId;
    /** Base units being returned — must not exceed inventory current stock. */
    private Integer baseQuantityReturned;
  }
}
