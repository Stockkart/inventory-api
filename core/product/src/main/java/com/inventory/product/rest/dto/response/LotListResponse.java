package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LotListResponse {
  private List<LotSummaryDto> data;
  private Map<String, Object> meta;
  private PageMeta page;
}

