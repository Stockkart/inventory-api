package com.inventory.pluginengine.menu;

import java.util.List;
import lombok.Data;

@Data
public class MenuSection {

  private String id;
  private String title;
  private Integer sortOrder;
  private List<MenuItem> items;
}
