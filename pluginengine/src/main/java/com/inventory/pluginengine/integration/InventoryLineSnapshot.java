package com.inventory.pluginengine.integration;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/** Inventory fields needed to build a cart line (menu direct mode or SKU billing). */
@Data
@Builder
public class InventoryLineSnapshot {

  private String inventoryId;
  private String name;
  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private BigDecimal costPrice;
  private String cgst;
  private String sgst;
  private int availableBaseCount;
  /** Canonical stock unit (e.g. BTL). */
  private String baseUnit;
}
