package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryListResponse {
  List<InventorySummaryDto> data;
  Map<String, Object> meta;
}

