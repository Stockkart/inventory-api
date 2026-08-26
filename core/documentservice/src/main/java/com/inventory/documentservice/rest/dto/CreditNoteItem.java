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
