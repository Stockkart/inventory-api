package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One line on a vendor purchase invoice, linked to created inventory after bulk registration.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseInvoiceLine {

  private int lineIndex;
  private String name;
  private String barcode;
  private Integer count;
  private BigDecimal costPrice;
  /** PTR / PTS fallback for accounting when {@link #costPrice} is absent or zero. */
  private BigDecimal priceToRetail;
  /** Set after successful inventory create for this line */
  private String inventoryId;
}
