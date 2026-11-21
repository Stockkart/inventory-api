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

  // --- Tax as resolved when the invoice was recorded -------------------------
  // Written once, at registration, so every reader agrees on what this line was
  // worth for tax. They were each deriving it instead -- the return from the
  // invoice header, the journal entry from raw cost, the credit note from the
  // pricing record -- and the three did not have to agree, because nothing made
  // them. Null on any line recorded before this was captured, which sends the
  // reader back to deriving it.
  //
  // The rate is not copied here. It lives on the lot's pricing (sgst + cgst), which
  // is where it is edited, and a copy would keep the old rate after an edit.

  /** Value the tax is charged on, after discounts and after any inclusive tax was taken out. */
  private BigDecimal taxableValue;
  private BigDecimal centralTax;
  private BigDecimal stateTax;
  private BigDecimal integratedTax;
}
