package com.inventory.pluginengine;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySearchResult {

  @Builder.Default
  private List<String> inventoryIds = new ArrayList<>();

  private String nextCursor;

  private int totalMatched;
}
