package com.inventory.plugins.medical.domain;

import com.inventory.pluginengine.InventoryExtensionDocument;
import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "inventory_ext_medical")
@CompoundIndexes({
  @CompoundIndex(name = "shop_expiry", def = "{'shopId': 1, 'expiryDate': 1}"),
  @CompoundIndex(name = "shop_batch", def = "{'shopId': 1, 'batchNo': 1}")
})
public class MedicalInventoryExtension implements InventoryExtensionDocument {

  @Id private String id;

  @Indexed(unique = true)
  private String inventoryId;

  @Indexed private String shopId;

  private String verticalId = "medical";

  private String batchNo;
  private Instant expiryDate;

  private Instant createdAt;
  private Instant updatedAt;
}
