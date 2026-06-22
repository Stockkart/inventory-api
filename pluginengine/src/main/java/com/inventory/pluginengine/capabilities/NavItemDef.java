package com.inventory.pluginengine.capabilities;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NavItemDef {

  private String id;
  private String label;
  private String path;
}
