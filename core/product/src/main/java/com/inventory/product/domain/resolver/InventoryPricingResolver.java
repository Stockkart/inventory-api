package com.inventory.product.domain.resolver;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.service.PricingService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.PricingData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves pricing for inventories. Used by AOP enricher.
 * Looks up by pricingId (from inventory) for fast _id retrieval; falls back to legacy fields.
 */
@Slf4j
@Component
public class InventoryPricingResolver {

  @Autowired
  private PricingService pricingService;

  @Autowired
  private MongoTemplate mongoTemplate;

  public PricingData resolvePricingInfo(Inventory inventory) {
    if (inventory == null) return null;
    if (org.springframework.util.StringUtils.hasText(inventory.getPricingId())) {
      return pricingService.findById(inventory.getPricingId())
          .map(this::from)
          .orElse(null);
    }
    return readLegacyPricing(inventory.getId());
  }

  /**
   * Returns map of inventoryId -> PricingData for the given inventories.
   */
  public Map<String, PricingData> resolvePricingInfoBatch(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    Map<String, PricingData> result = new HashMap<>();

    List<String> pricingIds = inventories.stream()
        .map(Inventory::getPricingId)
        .filter(org.springframework.util.StringUtils::hasText)
        .distinct()
        .toList();

    Map<String, Pricing> pricingMap = pricingService.findByIdIn(pricingIds);

    for (Inventory inv : inventories) {
      String invId = inv.getId();
      if (invId == null) continue;
      if (org.springframework.util.StringUtils.hasText(inv.getPricingId())) {
        Pricing p = pricingMap.get(inv.getPricingId());
        if (p != null) {
          result.put(invId, from(p));
        }
      } else {
        PricingData legacy = readLegacyPricing(invId);
        if (legacy != null) {
          result.put(invId, legacy);
        }
      }
    }
    return result;
  }

  private PricingData readLegacyPricing(String inventoryId) {
    PricingData data = mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(inventoryId)),
        PricingData.class,
        "inventory");
    return (data == null || data.isEmpty()) ? null : data;
  }

  private PricingData from(Pricing p) {
    return new PricingData(
        p.getMaximumRetailPrice(),
        p.getCostPrice(),
        p.getSellingPrice(),
        p.getAdditionalDiscount(),
        p.getSgst(),
        p.getCgst());
  }
}
