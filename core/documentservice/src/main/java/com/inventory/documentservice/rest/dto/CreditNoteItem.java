package com.inventory.documentservice.rest.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Line item for credit-note PDF generation.
 */
@Data
public class CreditNoteItem {
  private BigDecimal quantity;
  private String name;
  private String hsn;
  private String companyName;
  private String batchNo;
  private BigDecimal unitPrice;
  private BigDecimal taxableValue;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
  private BigDecimal lineTotal;
  private String cgst;
  private String sgst;
  /** Combined GST % for thermal display. */
  private BigDecimal gstPercent;

  // --- The line as the original document stated it ---------------------------
  // A note is filed by restating the transaction it reverses, and the printed note has to show
  // the same terms as the printed bill. What "unitPrice" means differs by role -- the selling
  // price on a credit note, the cost on a debit note -- so the terms that only one role has are
  // named for what they are and left null for the other.

  /** MRP, on both documents. */
  private BigDecimal maximumRetailPrice;
  /** Price to retail, which a purchase states alongside its cost. */
  private BigDecimal priceToRetail;
  /** Discount percentage as billed. */
  private BigDecimal discountPercent;
  /**
   * The scheme as billed, already worded.
   *
   * <p>Formatted by the assembler rather than here because the two roles read different fields
   * for it -- a sale scheme and a purchase scheme are different deals on the same goods.
   */
  private String schemeLabel;
  /** IGST where the supply crossed a state border; the two halves are then zero. */
  private BigDecimal igstAmount;

  /**
   * Quantity as a bill prints it: 3 rather than 3.0000, 2.5 kept as 2.5. Mongo hands the count
   * back scaled, and the raw BigDecimal carried those trailing zeros onto the paper. Rendered
   * here rather than in the template so a whole number like 30 does not come out as 3E+1.
   */
  public String getQuantityLabel() {
    if (quantity == null) {
      return "";
    }
    return quantity.stripTrailingZeros().toPlainString();
  }
}
