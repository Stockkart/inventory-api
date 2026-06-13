package com.inventory.plugins.sports.repository;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.plugins.sports.domain.SportsExtensionMongoRepository;
import com.inventory.plugins.sports.domain.SportsInventoryExtension;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SportsInventoryExtensionRepository implements InventoryExtensionRepository {

  private final SportsExtensionMongoRepository mongoRepository;

  public SportsInventoryExtensionRepository(SportsExtensionMongoRepository mongoRepository) {
    this.mongoRepository = mongoRepository;
  }

  @Override
  public String getVerticalId() {
    return "sports";
  }

  @Override
  public Optional<Map<String, Object>> findByInventoryId(String shopId, String inventoryId) {
    return mongoRepository
        .findByShopIdAndInventoryId(shopId, inventoryId)
        .map(this::toFieldMap);
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
                SportsInventoryExtension::getInventoryId,
                this::toFieldMap,
                (a, b) -> a,
                LinkedHashMap::new));
  }

  @Override
  public void upsert(String shopId, String inventoryId, Map<String, Object> fields) {
    SportsInventoryExtension doc =
        mongoRepository
            .findByShopIdAndInventoryId(shopId, inventoryId)
            .orElseGet(SportsInventoryExtension::new);
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

  private Map<String, Object> toFieldMap(SportsInventoryExtension doc) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (doc.getSport() != null) {
      out.put("sport", doc.getSport());
    }
    if (doc.getBrand() != null) {
      out.put("brand", doc.getBrand());
    }
    if (doc.getModel() != null) {
      out.put("model", doc.getModel());
    }
    if (doc.getWarrantyMonths() != null) {
      out.put("warrantyMonths", doc.getWarrantyMonths());
    }
    return out;
  }

  private void applyFields(SportsInventoryExtension doc, Map<String, Object> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    if (fields.containsKey("sport")) {
      doc.setSport(ExtensionFieldCoercion.asString(fields.get("sport")));
    }
    if (fields.containsKey("brand")) {
      doc.setBrand(ExtensionFieldCoercion.asString(fields.get("brand")));
    }
    if (fields.containsKey("model")) {
      doc.setModel(ExtensionFieldCoercion.asString(fields.get("model")));
    }
    if (fields.containsKey("warrantyMonths")) {
      doc.setWarrantyMonths(ExtensionFieldCoercion.asInteger(fields.get("warrantyMonths")));
    }
  }
}
