package com.inventory.documentservice.rest.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Invoice item DTO for invoice generation.
 */
@Data
public class InvoiceItem {
  private Integer quantity;
  private String name;
  private String hsn;
  private String companyName;
  private String expiryDate;
  private String batchNo;
  private BigDecimal maximumRetailPrice;
  private BigDecimal sellingPrice;
  private BigDecimal discount;
  private BigDecimal additionalDiscount; // Additional discount percentage
  private BigDecimal totalAmount; // Final amount after additionalDiscount and taxes
  private String scheme;
  private String inventoryId;
  private String cgst; // CGST rate (e.g., "2.5" for 2.5%)
  private String sgst; // SGST rate (e.g., "2.5" for 2.5%)
}


