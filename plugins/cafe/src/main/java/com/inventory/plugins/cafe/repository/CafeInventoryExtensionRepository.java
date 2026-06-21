package com.inventory.plugins.cafe.repository;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.plugins.cafe.domain.CafeExtensionMongoRepository;
import com.inventory.plugins.cafe.domain.CafeInventoryExtension;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CafeInventoryExtensionRepository implements InventoryExtensionRepository {

  private final CafeExtensionMongoRepository mongoRepository;

  public CafeInventoryExtensionRepository(CafeExtensionMongoRepository mongoRepository) {
    this.mongoRepository = mongoRepository;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
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
                CafeInventoryExtension::getInventoryId,
                this::toFieldMap,
                (a, b) -> a,
                LinkedHashMap::new));
  }

  @Override
  public void upsert(String shopId, String inventoryId, Map<String, Object> fields) {
    CafeInventoryExtension doc =
        mongoRepository
            .findByShopIdAndInventoryId(shopId, inventoryId)
            .orElseGet(CafeInventoryExtension::new);
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

  private Map<String, Object> toFieldMap(CafeInventoryExtension doc) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (doc.getIngredientType() != null) {
      out.put("ingredientType", doc.getIngredientType());
    }
    if (doc.getUnitOfMeasure() != null) {
      out.put("unitOfMeasure", doc.getUnitOfMeasure());
    }
    return out;
  }

  private void applyFields(CafeInventoryExtension doc, Map<String, Object> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    if (fields.containsKey("ingredientType")) {
      doc.setIngredientType(ExtensionFieldCoercion.asString(fields.get("ingredientType")));
    }
    if (fields.containsKey("unitOfMeasure")) {
      doc.setUnitOfMeasure(ExtensionFieldCoercion.asString(fields.get("unitOfMeasure")));
    }
  }
}
