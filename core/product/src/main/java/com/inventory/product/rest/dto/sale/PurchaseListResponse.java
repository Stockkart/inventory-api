package com.inventory.product.rest.dto.sale;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseListResponse {
  private List<PurchaseSummaryDto> purchases;
  private int page;
  private int limit;
  private long total;
  private int totalPages;
}

