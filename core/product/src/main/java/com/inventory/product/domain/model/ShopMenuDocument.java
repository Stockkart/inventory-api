package com.inventory.product.domain.model;

import com.inventory.pluginengine.menu.MenuSection;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "shop_menus")
@CompoundIndex(name = "shop_vertical", def = "{'shopId': 1, 'verticalId': 1}", unique = true)
public class ShopMenuDocument {

  @Id private String id;
  private String shopId;
  private String verticalId;
  private String pluginVersion;
  private Integer revision;
  private List<MenuSection> sections;
  private Instant updatedAt;
  private String updatedBy;
}
