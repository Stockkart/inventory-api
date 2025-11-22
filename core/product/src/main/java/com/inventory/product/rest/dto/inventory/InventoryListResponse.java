package com.inventory.product.rest.dto.inventory;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class InventoryListResponse {
  @Singular("item")
  List<InventorySummaryDto> data;
  Map<String, Object> meta;
}

