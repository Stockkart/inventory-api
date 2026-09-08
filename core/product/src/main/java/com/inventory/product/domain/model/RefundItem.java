package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.SchemeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents an item that was refunded.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundItem {

  /**
   * Inventory ID (lotId) of the refunded item.
   */
  private String inventoryId;

  /**
   * Name of the refunded product.
   */
  private String name;

  /**
   * Quantity refunded.
   */
  private Integer quantity;

  /**
   * Selling price per unit at time of purchase.
   */
  private BigDecimal priceToRetail;

  /**
   * Total refund amount for this item (priceToRetail * quantity).
   */
  private BigDecimal itemRefundAmount;

  private BigDecimal taxableValue;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
  private BigDecimal cogsAmount;
  private BigDecimal lineReturnTotal;

  // --- As the line was billed ------------------------------------------------
  // A credit note reverses a sale, and a return is filed by stating what the original supply
  // was: the same MRP, the same discount, the same scheme, the same rate. Recording only the
  // refund total left the note unable to show any of that, so the two documents described the
  // same goods in different terms and the figures had to be matched by hand at filing.
  //
  // Snapshotted rather than read back from the sale, for the reason the sale itself snapshots
  // them: a line outlives the lot it came from, and a note must stay faithful to the invoice it
  // credits even after the goods and their pricing have moved on.

  /** MRP printed on the original invoice line. */
  private BigDecimal maximumRetailPrice;
  /** Additional discount percentage applied on the sale. */
  private BigDecimal saleAdditionalDiscount;
  /** State and central GST rates as charged, e.g. "2.5". */
  private String sgst;
  private String cgst;
  /** Sale scheme as billed, so the note states the deal the goods were sold under. */
  private SchemeType schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}

