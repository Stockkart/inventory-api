package com.inventory.documentservice.rest.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Invoice item DTO for invoice generation.
 */
@Data
public class InvoiceItem {
  private Integer quantity;
  private String pack;
  private String name;
  private String hsn;
  private String sac;
  private String mfgExpDate;
  private String batchNo;
  private BigDecimal maximumRetailPrice;
  private BigDecimal sellingPrice;
  private String scheme;
  private String inventoryId;
}

