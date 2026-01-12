package com.inventory.ocr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO representing an inventory item parsed from an invoice image.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedInventoryItem {
  private String code;              // Product code (can be used as barcode)
  private String hsn;                // HSN code
  private String name;              // Product name/description
  private String batchNo;           // Batch number
  private String manufactureDate;   // Manufacture date (as string, e.g., "NOV-24")
  private String expiryDate;        // Expiry date (as string, e.g., "OCT-27")
  private Integer quantity;         // Quantity
  private BigDecimal rate;          // Rate per unit (cost price)
  private BigDecimal mrp;            // Maximum Retail Price
  private BigDecimal reducedMrp;     // Reduced MRP (selling price)
  private String packageDetail;      // Package detail (e.g., "1 X 50")
}

