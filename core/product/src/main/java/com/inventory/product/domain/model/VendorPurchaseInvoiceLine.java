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
  /** PTR / PTS fallback when {@link #costPrice} is absent or zero. */
  private BigDecimal priceToRetail;
  /**
   * The lot these goods made, and the whole of what the line knows about them.
   *
   * <p>Everything else is read through it: the lot names its product, which
   * carries the HSN, and its pricing, which carries the tax. A line whose goods
   * were sold before the shop was migrated has a lot too -- one holding nothing,
   * which is what a delivery long since sold actually left behind.
   */
  private String inventoryId;

}
