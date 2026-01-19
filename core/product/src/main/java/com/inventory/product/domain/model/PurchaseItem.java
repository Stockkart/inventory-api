package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseItem {

  private String inventoryId;
  private String name;
  private Integer quantity;
  private BigDecimal maximumRetailPrice;
  private BigDecimal sellingPrice;
  private BigDecimal discount;
  private BigDecimal additionalDiscount; // Additional discount percentage from inventory
  private BigDecimal totalAmount; // Final amount after additionalDiscount and taxes (CGST + SGST)
  private String sgst; // SGST rate from inventory (e.g., "9" for 9%)
  private String cgst; // CGST rate from inventory (e.g., "9" for 9%)
}

