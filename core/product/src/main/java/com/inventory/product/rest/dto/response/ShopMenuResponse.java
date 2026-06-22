package com.inventory.product.rest.dto.response;

import com.inventory.pluginengine.menu.MenuSection;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class ShopMenuResponse {

  private String id;
  private String shopId;
  private String verticalId;
  private String pluginVersion;
  private Integer revision;
  private List<MenuSection> sections;
  private Instant updatedAt;
  private String updatedBy;
}
