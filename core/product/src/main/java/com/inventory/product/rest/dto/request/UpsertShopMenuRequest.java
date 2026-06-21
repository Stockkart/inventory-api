package com.inventory.product.rest.dto.request;

import com.inventory.pluginengine.menu.MenuSection;
import java.util.List;
import lombok.Data;

@Data
public class UpsertShopMenuRequest {

  private Integer revision;
  private List<MenuSection> sections;
}
