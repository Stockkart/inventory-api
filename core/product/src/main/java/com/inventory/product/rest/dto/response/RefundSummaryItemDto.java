package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One refunded line for list/history views */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundSummaryItemDto {

  private String inventoryId;

  private String name;

  private Integer quantity;

  private BigDecimal priceToRetail;

  private BigDecimal itemRefundAmount;
}
