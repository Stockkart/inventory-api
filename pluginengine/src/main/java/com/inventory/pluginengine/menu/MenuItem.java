package com.inventory.pluginengine.menu;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class MenuItem {

  private String id;
  private String name;
  private BigDecimal sellingPrice;
  private MenuSellMode sellMode;
  private String inventoryId;
  private Boolean available;
  private String cgst;
  private String sgst;
}
