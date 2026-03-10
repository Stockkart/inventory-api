package com.inventory.product.domain.model.pricing;

import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.ShopRepository;
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
 * Handles inventory pricing read: resolve pricing (from port or legacy) and enrich inventory.
 */
@Slf4j
@Component
public class InventoryPricingReadHandler {

  @Autowired
  private InventoryPricingAdapter pricingPort;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShopRepository shopRepository;

  public PricingReadDto resolve(Inventory inventory) {
    if (inventory == null) return null;
    if (StringUtils.hasText(inventory.getPricingId())) {
      log.debug("Resolving pricing from Pricing table for inventory {} (pricingId={})", inventory.getId(), inventory.getPricingId());
      return pricingPort.findById(inventory.getPricingId()).orElse(null);
    }
    log.debug("Resolving legacy pricing from inventory document for inventory {}", inventory.getId());
    return readLegacyPricing(inventory.getId());
  }

  public Map<String, PricingReadDto> resolveBatch(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return Map.of();

    List<String> pricingIds = inventories.stream()
        .map(Inventory::getPricingId)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();

    Map<String, PricingReadDto> pricingMap = pricingPort.findByIdIn(pricingIds);
    Map<String, PricingReadDto> result = new HashMap<>();

    for (Inventory inv : inventories) {
      String invId = inv.getId();
      if (invId == null) continue;
      if (StringUtils.hasText(inv.getPricingId())) {
        PricingReadDto p = pricingMap.get(inv.getPricingId());
        if (p != null) result.put(invId, p);
      } else {
        PricingReadDto legacy = readLegacyPricing(invId);
        if (legacy != null) result.put(invId, legacy);
      }
    }
    return result;
  }

  public void enrich(Inventory inventory) {
    if (inventory == null) return;
    PricingReadDto p = resolve(inventory);
    if (p != null) {
      log.debug("enrich inventory {} applying sgst={} cgst={}", inventory.getId(), p.getSgst(), p.getCgst());
      applyPricing(inventory, p);
    }
  }

  public void enrich(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return;
    Map<String, PricingReadDto> map = resolveBatch(inventories);
    for (Inventory inv : inventories) {
      PricingReadDto p = map.get(inv.getId());
      if (p != null) applyPricing(inv, p);
    }
  }

  private PricingReadDto readLegacyPricing(String inventoryId) {
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
    BigDecimal ptr = getBigDecimal(doc, "priceToRetail");
    if (ptr == null) ptr = getBigDecimal(doc, "sellingPrice"); // backward compat
    BigDecimal discount = getBigDecimal(doc, "additionalDiscount");
    String sgst = toStringOrNull(doc.get("sgst"));
    String cgst = toStringOrNull(doc.get("cgst"));
    log.debug("readLegacyPricing inventoryId={} docKeys={} sgst={} (raw={}) cgst={} (raw={})",
        inventoryId, doc.keySet(), sgst, doc.get("sgst"), cgst, doc.get("cgst"));
    if (mrp == null && cost == null && ptr == null && discount == null && sgst == null && cgst == null) {
      return null;
    }
    return new PricingReadDto(mrp, cost, ptr, null, null, ptr, discount, sgst, cgst);
  }

  private void applyPricing(Inventory inv, PricingReadDto p) {
    inv.setMaximumRetailPrice(p.getMaximumRetailPrice());
    inv.setCostPrice(p.getCostPrice());
    inv.setRates(p.getRates());
    inv.setDefaultRate(StringUtils.hasText(p.getDefaultRate())
        ? p.getDefaultRate()
        : (p.getPriceToRetail() != null ? "priceToRetail" : null));
    inv.setPriceToRetail(p.getPriceToRetail());
    inv.setSellingPrice(p.getEffectivePrice());
    inv.setAdditionalDiscount(p.getAdditionalDiscount());
    if (inv.getBillingMode() == BillingMode.BASIC) {
      inv.setSgst(null);
      inv.setCgst(null);
      return;
    }
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
}
