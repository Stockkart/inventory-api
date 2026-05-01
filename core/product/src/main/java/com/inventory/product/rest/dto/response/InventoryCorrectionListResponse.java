package com.inventory.product.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCorrectionListResponse {
  private List<InventoryCorrectionDto> corrections;
  private PageMeta page;
}

