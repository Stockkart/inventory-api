package com.inventory.product.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateListResponse {
  private List<EstimateSummaryDto> estimates;
  private int page;
  private int size;
  private long total;
  private int totalPages;
}
