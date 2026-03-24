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
  private String inventoryId;
  private String cgst; // CGST rate (e.g., "2.5" for 2.5%)
  private String sgst; // SGST rate (e.g., "2.5" for 2.5%)
}


