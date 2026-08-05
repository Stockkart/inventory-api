package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisStockRowDto {
  private String inventoryId;
  private String productId;
  private String name;
  private String barcode;
  private String lotId;
  private BigDecimal onHand;
  private Integer threshold;
  private BigDecimal costPrice;
  private BigDecimal sellPrice;
  private BigDecimal costValue;
  private BigDecimal sellValue;
  private BigDecimal potentialProfit;
  private boolean lowStock;
  private boolean deadStock;
  private BigDecimal soldCount;
}
