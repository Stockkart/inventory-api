package com.inventory.documentservice.rest.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Invoice item DTO for invoice generation.
 */
@Data
public class InvoiceItem {
  private Integer quantity;
  private String name;
  private String hsn;
  private String sac;
  private String companyName;
  private String expiryDate;
  private String batchNo;
  private BigDecimal maximumRetailPrice;
  private BigDecimal sellingPrice;
  private BigDecimal discount;
  private String scheme;
  private String inventoryId;
}

