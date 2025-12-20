package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LotDetailDto {
  private String lotId;
  private String shopId;
  private Instant createdAt;
  private Instant lastUpdated;
  private List<InventorySummaryDto> items; // All products in this lot
  private Integer totalProductCount;
  private Integer totalCurrentCount;
}

