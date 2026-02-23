package com.inventory.product.domain.model.pricing;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import com.inventory.pricing.service.PricingService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.PricingData;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
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
      log.debug("Resolving pricing from Pricing table for inventory {} (pricingId={})", inventory.getId(), inventory.getPricingId());
      return pricingService.findById(inventory.getPricingId())
          .map(this::toPricingData)
          .orElse(null);
    }
    log.debug("Resolving legacy pricing from inventory document for inventory {}", inventory.getId());
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
    if (p != null) {
      log.debug("enrich inventory {} applying sgst={} cgst={}", inventory.getId(), p.getSgst(), p.getCgst());
      applyPricing(inventory, p);
    }
  }

  public void enrich(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return;
    Map<String, PricingData> map = resolveBatch(inventories);
    for (Inventory inv : inventories) {
      PricingData p = map.get(inv.getId());
      if (p != null) applyPricing(inv, p);
    }
  }

  // --- Write: persist from entity (create) or context (update) ---

  public void persistOnSave(Inventory inventory) {
    try {
      // Create: new inventory with pricing data on entity (from mapper)
      if (inventory.getId() == null && hasPricingData(inventory) && StringUtils.hasText(inventory.getShopId())) {
        CreatePricingRequest req = new CreatePricingRequest();
        req.setShopId(inventory.getShopId());
        req.setMaximumRetailPrice(inventory.getMaximumRetailPrice());
        req.setCostPrice(inventory.getCostPrice());
        req.setSellingPrice(inventory.getSellingPrice());
        req.setRates(inventory.getRates());
        req.setDefaultRate(inventory.getDefaultRate());
        req.setAdditionalDiscount(inventory.getAdditionalDiscount());
        req.setSgst(resolveSgst(inventory.getSgst(), inventory.getShopId()));
        req.setCgst(resolveCgst(inventory.getCgst(), inventory.getShopId()));
        var pricing = pricingService.createAndReturnEntity(req);
        inventory.setPricingId(pricing.getId());
        return;
      }
      // Update: from context (service update sets it)
      InventoryPricingContext.Context ctx = InventoryPricingContext.get();
      if (ctx != null && ctx.type == InventoryPricingContext.Type.UPDATE && ctx.updateRequest != null) {
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

  private boolean hasPricingData(Inventory inv) {
    return inv.getMaximumRetailPrice() != null || inv.getCostPrice() != null
        || inv.getSellingPrice() != null || inv.getAdditionalDiscount() != null
        || (inv.getRates() != null && !inv.getRates().isEmpty());
  }

  // --- Helpers ---

  private PricingData readLegacyPricing(String inventoryId) {
    Object idQuery = isValidObjectId(inventoryId) ? new ObjectId(inventoryId) : inventoryId;
    Document doc = mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(idQuery)),
        Document.class,
        "inventory");
    if (doc == null) {
      log.debug("readLegacyPricing: no document found for inventoryId={}", inventoryId);
      return null;
    }
    BigDecimal mrp = getBigDecimal(doc, "maximumRetailPrice");
    BigDecimal cost = getBigDecimal(doc, "costPrice");
    BigDecimal selling = getBigDecimal(doc, "sellingPrice");
    BigDecimal discount = getBigDecimal(doc, "additionalDiscount");
    String sgst = toStringOrNull(doc.get("sgst"));
    String cgst = toStringOrNull(doc.get("cgst"));
    log.debug("readLegacyPricing inventoryId={} docKeys={} sgst={} (raw={}) cgst={} (raw={})",
        inventoryId, doc.keySet(), sgst, doc.get("sgst"), cgst, doc.get("cgst"));
    if (mrp == null && cost == null && selling == null && discount == null && sgst == null && cgst == null) {
      return null;
    }
    return new PricingData(mrp, cost, selling, null, null, discount, sgst, cgst);
  }

  private static boolean isValidObjectId(String s) {
    if (s == null || s.length() != 24) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) return false;
    }
    return true;
  }

  private static BigDecimal getBigDecimal(Document doc, String key) {
    Object v = doc.get(key);
    if (v == null) return null;
    if (v instanceof BigDecimal) return (BigDecimal) v;
    if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
    try {
      return new BigDecimal(v.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private static String toStringOrNull(Object v) {
    if (v == null) return null;
    String s = v.toString().trim();
    return s.isEmpty() ? null : s;
  }

  private PricingData toPricingData(Pricing p) {
    PricingData data = new PricingData(
        p.getMaximumRetailPrice(),
        p.getCostPrice(),
        p.getSellingPrice(),
        p.getRates(),
        p.getDefaultRate(),
        p.getAdditionalDiscount(),
        p.getSgst(),
        p.getCgst());
    log.debug("toPricingData pricingId={} sgst={} cgst={}", p.getId(), p.getSgst(), p.getCgst());
    return data;
  }

  private void applyPricing(Inventory inv, PricingData p) {
    inv.setMaximumRetailPrice(p.getMaximumRetailPrice());
    inv.setCostPrice(p.getCostPrice());
    inv.setRates(p.getRates());
    inv.setDefaultRate(StringUtils.hasText(p.getDefaultRate())
        ? p.getDefaultRate()
        : (p.getSellingPrice() != null ? "SellingPrice" : null));
    inv.setSellingPrice(p.getEffectiveSellingPrice());
    inv.setAdditionalDiscount(p.getAdditionalDiscount());
    String sgst = p.getSgst();
    String cgst = p.getCgst();
    if (!StringUtils.hasText(sgst) || !StringUtils.hasText(cgst)) {
      var shop = shopRepository.findById(inv.getShopId()).orElse(null);
      if (shop != null) {
        if (!StringUtils.hasText(sgst)) sgst = shop.getSgst();
        if (!StringUtils.hasText(cgst)) cgst = shop.getCgst();
      }
    }
    inv.setSgst(sgst);
    inv.setCgst(cgst);
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
