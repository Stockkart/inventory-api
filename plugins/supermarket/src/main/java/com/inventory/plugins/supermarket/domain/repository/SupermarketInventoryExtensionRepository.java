package com.inventory.plugins.supermarket.domain.repository;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.plugins.supermarket.domain.model.SupermarketInventoryExtension;
import com.inventory.plugins.supermarket.domain.repository.SupermarketExtensionMongoRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SupermarketInventoryExtensionRepository implements InventoryExtensionRepository {

  private final SupermarketExtensionMongoRepository mongoRepository;

  public SupermarketInventoryExtensionRepository(SupermarketExtensionMongoRepository mongoRepository) {
    this.mongoRepository = mongoRepository;
  }

  @Override
  public String getVerticalId() {
    return "supermarket";
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
                SupermarketInventoryExtension::getInventoryId,
                this::toFieldMap,
                (a, b) -> a,
                LinkedHashMap::new));
  }

  @Override
  public void upsert(String shopId, String inventoryId, Map<String, Object> fields) {
    SupermarketInventoryExtension doc =
        mongoRepository
            .findByShopIdAndInventoryId(shopId, inventoryId)
            .orElseGet(SupermarketInventoryExtension::new);
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

  private Map<String, Object> toFieldMap(SupermarketInventoryExtension doc) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (doc.getBatchNo() != null) {
      out.put("batchNo", doc.getBatchNo());
    }
    if (doc.getExpiryDate() != null) {
      out.put("expiryDate", doc.getExpiryDate());
    }
    return out;
  }

  private void applyFields(SupermarketInventoryExtension doc, Map<String, Object> fields) {
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
