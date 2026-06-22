package com.inventory.product.rest.dto.response;

import java.util.List;
import lombok.Data;

/** Sell surface catalog: menu sections plus vertical-specific direct stock (cafe). */
@Data
public class SellCatalogResponse {

  private ShopMenuResponse menu;
  private List<InventorySummaryDto> directStock;
}
