package com.inventory.plugins.grocery.domain.repository;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.plugins.grocery.domain.model.GroceryInventoryExtension;
import com.inventory.plugins.grocery.domain.repository.GroceryExtensionMongoRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GroceryInventoryExtensionRepository implements InventoryExtensionRepository {

  private final GroceryExtensionMongoRepository mongoRepository;

  public GroceryInventoryExtensionRepository(GroceryExtensionMongoRepository mongoRepository) {
    this.mongoRepository = mongoRepository;
  }

  @Override
  public String getVerticalId() {
    return "grocery";
  }

  @Override
  public Optional<Map<String, Object>> findByInventoryId(String shopId, String inventoryId) {
    return mongoRepository.findByShopIdAndInventoryId(shopId, inventoryId).map(this::toFieldMap);
  }

  @Override
  public Map<String, Map<String, Object>> findByInventoryIds(
      String shopId, List<String> inventoryIds) {
    if (inventoryIds == null || inventoryIds.isEmpty()) {
      return Map.of();
    }
    return mongoRepository.findByShopIdAndInventoryIdIn(shopId, inventoryIds).stream()
        .collect(
            Collectors.toMap(
                GroceryInventoryExtension::getInventoryId,
                this::toFieldMap,
                (a, b) -> a,
                LinkedHashMap::new));
  }

  @Override
  public void upsert(String shopId, String inventoryId, Map<String, Object> fields) {
    GroceryInventoryExtension doc =
        mongoRepository
            .findByShopIdAndInventoryId(shopId, inventoryId)
            .orElseGet(GroceryInventoryExtension::new);
    Instant now = Instant.now();
    if (doc.getId() == null) {
      doc.setCreatedAt(now);
    }
    doc.setShopId(shopId);
    doc.setInventoryId(inventoryId);
    doc.setVerticalId(getVerticalId());
    applyFields(doc, fields);
    doc.setUpdatedAt(now);
    mongoRepository.save(doc);
  }

  private Map<String, Object> toFieldMap(GroceryInventoryExtension doc) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (doc.getBatchNo() != null) {
      out.put("batchNo", doc.getBatchNo());
    }
    if (doc.getExpiryDate() != null) {
      out.put("expiryDate", doc.getExpiryDate());
    }
    return out;
  }

  private void applyFields(GroceryInventoryExtension doc, Map<String, Object> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    if (fields.containsKey("batchNo")) {
      doc.setBatchNo(ExtensionFieldCoercion.asString(fields.get("batchNo")));
    }
    if (fields.containsKey("expiryDate")) {
      doc.setExpiryDate(ExtensionFieldCoercion.asInstant(fields.get("expiryDate")));
    }
  }
}
