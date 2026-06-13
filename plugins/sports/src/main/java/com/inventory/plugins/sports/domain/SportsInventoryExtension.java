package com.inventory.plugins.sports.domain;

import com.inventory.pluginengine.InventoryExtensionDocument;
import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "inventory_ext_sports")
@CompoundIndexes({
  @CompoundIndex(name = "shop_sport", def = "{'shopId': 1, 'sport': 1}"),
  @CompoundIndex(name = "shop_brand", def = "{'shopId': 1, 'brand': 1}")
})
public class SportsInventoryExtension implements InventoryExtensionDocument {

  @Id private String id;

  @Indexed(unique = true)
  private String inventoryId;

  @Indexed private String shopId;

  private String verticalId = "sports";

  private String sport;
  private String brand;
  private String model;
  private Integer warrantyMonths;

  private Instant createdAt;
  private Instant updatedAt;
}
