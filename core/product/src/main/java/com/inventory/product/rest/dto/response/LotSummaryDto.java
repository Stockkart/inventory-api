package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LotSummaryDto {
  private String lotId;
  private Integer productCount;
  private Instant createdAt;
  private Instant lastUpdated;
  private String firstProductName; // Optional: for display
}

