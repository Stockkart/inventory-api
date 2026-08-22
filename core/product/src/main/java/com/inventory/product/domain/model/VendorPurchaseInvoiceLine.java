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
  /** Set after successful inventory create for this line */
  private String inventoryId;

  /**
   * What the supplier's invoice states about the goods and the tax on them.
   *
   * <p>The stock lot normally answers these, and for a purchase entered through
   * the application it still does. They are recorded here for a line that has no
   * lot to answer for it -- an invoice imported from a shop's previous system,
   * whose deliveries were already brought in as opening stock, so recreating
   * them as lots would count the same goods twice.
   *
   * <p>Without them the inward return can only be built from what remains in
   * stock, which is what was left rather than what was bought.
   */
  private String hsn;

  private String batchNo;

  private String companyName;

  /** As printed: stated to the month, so parsing it would invent a day. */
  private String expiryDate;

  private BigDecimal maximumRetailPrice;

  /** The line net of discount and before tax. */
  private BigDecimal taxableValue;

  /** The line net of discount and inclusive of tax. */
  private BigDecimal totalAmount;

  /** Rates as percentages, e.g. "2.5". */
  private String sgst;

  private String cgst;
}
