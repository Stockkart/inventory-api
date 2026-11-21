package com.inventory.documentservice.rest.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Invoice item DTO for invoice generation.
 */
@Data
public class InvoiceItem {
  private BigDecimal quantity;
  private String name;
  private String hsn;

  /** How the goods are packed, as the trade bill's PACK column states it: "1X120", "1X200 ML". */
  private String pack;
  private String companyName;
  private String expiryDate;
  private String batchNo;
  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private BigDecimal discount;
  private BigDecimal saleAdditionalDiscount; // Additional discount percentage
  private BigDecimal totalAmount; // Final amount after additionalDiscount and taxes
  private Integer scheme; // Inventory scheme (free units in stock) - from inventory
  private Integer schemePayFor; // Selling scheme: pay for X (e.g. 10)
  private Integer schemeFree; // Selling scheme: get Y free (e.g. 2) → "2 free on 10"
  // A percentage scheme has no pay-for/free pair to print - the line carries the rate itself.
  private BigDecimal schemePercentage; // Selling scheme: percentage free (e.g. 2 for 2%)
  private String inventoryId;
  private String cgst; // CGST rate (e.g., "2.5" for 2.5%)
  private String sgst; // SGST rate (e.g., "2.5" for 2.5%)
  /** Combined GST % for thermal/receipt display (cgst + sgst). */
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
