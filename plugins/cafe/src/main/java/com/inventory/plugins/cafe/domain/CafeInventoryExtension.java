package com.inventory.plugins.cafe.domain;

import com.inventory.pluginengine.InventoryExtensionDocument;
import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "inventory_ext_cafe")
@CompoundIndexes({
  @CompoundIndex(name = "shop_ingredient_type", def = "{'shopId': 1, 'ingredientType': 1}")
})
public class CafeInventoryExtension implements InventoryExtensionDocument {

  @Id private String id;

  @Indexed(unique = true)
  private String inventoryId;

  @Indexed private String shopId;

  private String verticalId = "cafe";

  private String ingredientType;
  private String unitOfMeasure;
  /** When true, item appears on cafe sell screen and deducts stock at checkout. */
  private Boolean sellDirect;

  private Instant createdAt;
  private Instant updatedAt;
}
