package com.inventory.product.domain.model.pricing;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import com.inventory.pricing.service.PricingService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.PricingData;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles all inventory pricing logic: resolve (read), enrich, and persist (write).
 * Used by InventoryPricingAspect.
 */
@Slf4j
@Component
public class InventoryPricingHandler {

  @Autowired
  private PricingService pricingService;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShopRepository shopRepository;

  // --- Read: resolve pricing ---

  public PricingData resolve(Inventory inventory) {
    if (inventory == null) return null;
    if (StringUtils.hasText(inventory.getPricingId())) {
      return pricingService.findById(inventory.getPricingId())
          .map(this::toPricingData)
          .orElse(null);
    }
    return readLegacyPricing(inventory.getId());
  }

  public Map<String, PricingData> resolveBatch(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return Map.of();

    List<String> pricingIds = inventories.stream()
        .map(Inventory::getPricingId)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();

    Map<String, Pricing> pricingMap = pricingService.findByIdIn(pricingIds);
    Map<String, PricingData> result = new HashMap<>();

    for (Inventory inv : inventories) {
      String invId = inv.getId();
      if (invId == null) continue;
      if (StringUtils.hasText(inv.getPricingId())) {
        Pricing p = pricingMap.get(inv.getPricingId());
        if (p != null) result.put(invId, toPricingData(p));
      } else {
        PricingData legacy = readLegacyPricing(invId);
        if (legacy != null) result.put(invId, legacy);
      }
    }
    return result;
  }

  // --- Read: enrich inventory with pricing ---

  public void enrich(Inventory inventory) {
    if (inventory == null) return;
    PricingData p = resolve(inventory);
    if (p != null) applyPricing(inventory, p);
  }

  public void enrich(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return;
    Map<String, PricingData> map = resolveBatch(inventories);
    for (Inventory inv : inventories) {
      PricingData p = map.get(inv.getId());
      if (p != null) applyPricing(inv, p);
    }
  }

  // --- Write: persist from context ---

  public void persistFromContext(Inventory inventory) {
    InventoryPricingContext.Context ctx = InventoryPricingContext.get();
    if (ctx == null) return;

    try {
      if (ctx.type == InventoryPricingContext.Type.CREATE && ctx.createRequest != null) {
        CreateInventoryRequest req = ctx.createRequest;
        CreatePricingRequest pricingReq = new CreatePricingRequest();
        pricingReq.setShopId(ctx.shopId);
        pricingReq.setMaximumRetailPrice(req.getMaximumRetailPrice());
        pricingReq.setCostPrice(req.getCostPrice());
        pricingReq.setSellingPrice(req.getSellingPrice());
        pricingReq.setAdditionalDiscount(req.getAdditionalDiscount());
        pricingReq.setSgst(resolveSgst(req.getSgst(), ctx.shopId));
        pricingReq.setCgst(resolveCgst(req.getCgst(), ctx.shopId));

        var pricing = pricingService.createAndReturnEntity(pricingReq);
        inventory.setPricingId(pricing.getId());
      } else if (ctx.type == InventoryPricingContext.Type.UPDATE && ctx.updateRequest != null) {
        UpdateInventoryRequest req = ctx.updateRequest;
        if (req.getAdditionalDiscount() != null && StringUtils.hasText(inventory.getPricingId())) {
          UpdatePricingRequest pricingReq = new UpdatePricingRequest();
          pricingReq.setAdditionalDiscount(req.getAdditionalDiscount());
          pricingService.update(inventory.getPricingId(), pricingReq);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to persist pricing for inventory {}: {}", inventory.getId(), e.getMessage());
    }
  }

  // --- Helpers ---

  private PricingData readLegacyPricing(String inventoryId) {
    PricingData data = mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(inventoryId)),
        PricingData.class,
        "inventory");
    return (data == null || data.isEmpty()) ? null : data;
  }

  private PricingData toPricingData(Pricing p) {
    return new PricingData(
        p.getMaximumRetailPrice(),
        p.getCostPrice(),
        p.getSellingPrice(),
        p.getAdditionalDiscount(),
        p.getSgst(),
        p.getCgst());
  }

  private void applyPricing(Inventory inv, PricingData p) {
    inv.setMaximumRetailPrice(p.getMaximumRetailPrice());
    inv.setCostPrice(p.getCostPrice());
    inv.setSellingPrice(p.getSellingPrice());
    inv.setAdditionalDiscount(p.getAdditionalDiscount());
    inv.setSgst(p.getSgst());
    inv.setCgst(p.getCgst());
  }

  private String resolveSgst(String fromRequest, String shopId) {
    if (StringUtils.hasText(fromRequest)) return fromRequest;
    if (!StringUtils.hasText(shopId)) return null;
    return shopRepository.findById(shopId).map(Shop::getSgst).orElse(null);
  }

  private String resolveCgst(String fromRequest, String shopId) {
    if (StringUtils.hasText(fromRequest)) return fromRequest;
    if (!StringUtils.hasText(shopId)) return null;
    return shopRepository.findById(shopId).map(Shop::getCgst).orElse(null);
  }
}
