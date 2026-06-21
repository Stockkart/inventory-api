package com.inventory.pluginengine.capabilities;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PurchaseSearchConfig {

  private List<String> fields;
}
