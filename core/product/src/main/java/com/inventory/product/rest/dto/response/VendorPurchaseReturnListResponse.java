package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnListResponse {

  private List<VendorPurchaseReturnSummaryDto> returns;

  private int page;

  private int limit;

  private long total;

  private int totalPages;
}
