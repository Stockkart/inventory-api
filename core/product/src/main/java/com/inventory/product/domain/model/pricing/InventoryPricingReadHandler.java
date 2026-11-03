package com.inventory.product.domain.model.pricing;

import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.LotPricing;
import com.inventory.pricing.api.InventoryPricing;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.SchemeType;
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
import java.util.ArrayList;
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
  private InventoryPricing pricingPort;

  @Autowired
  private MongoTemplate mongoTemplate;

  public LotPricing resolve(Inventory inventory) {
    if (inventory == null) return null;
    if (StringUtils.hasText(inventory.getPricingId())) {
      log.debug("Resolving pricing from Pricing table for inventory {} (pricingId={})", inventory.getId(), inventory.getPricingId());
      return pricingPort.findById(inventory.getPricingId()).orElse(null);
    }
    log.debug("Resolving legacy pricing from inventory document for inventory {}", inventory.getId());
    return readLegacyPricing(inventory.getId());
  }

  /**
   * Pricing for a list of lots in at most two queries: one for the pricing records, one for the
   * legacy lots that still carry their price on the inventory document. Reading legacy lots one at
   * a time made every list read (dashboard, search, low stock) cost one round trip per legacy lot.
   */
  public Map<String, LotPricing> resolveBatch(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return Map.of();

    List<String> pricingIds = inventories.stream()
        .map(Inventory::getPricingId)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
    List<String> legacyIds = inventories.stream()
        .filter(inv -> inv.getId() != null && !StringUtils.hasText(inv.getPricingId()))
        .map(Inventory::getId)
        .distinct()
        .toList();

    Map<String, LotPricing> pricingMap = pricingIds.isEmpty() ? Map.of() : pricingPort.findByIdIn(pricingIds);
    Map<String, LotPricing> legacyMap = readLegacyPricingBatch(legacyIds);
    Map<String, LotPricing> result = new HashMap<>();

    for (Inventory inv : inventories) {
      String invId = inv.getId();
      if (invId == null) continue;
      LotPricing p = StringUtils.hasText(inv.getPricingId())
          ? pricingMap.get(inv.getPricingId())
          : legacyMap.get(invId);
      if (p != null) result.put(invId, p);
    }
    return result;
  }

  public void enrich(Inventory inventory) {
    if (inventory == null) return;
    LotPricing p = resolve(inventory);
    if (p != null) {
      log.debug("enrich inventory {} applying sgst={} cgst={}", inventory.getId(), p.sgst(), p.cgst());
      applyPricing(inventory, p);
    }
  }

  public void enrich(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return;
    Map<String, LotPricing> map = resolveBatch(inventories);
    for (Inventory inv : inventories) {
      LotPricing p = map.get(inv.getId());
      if (p != null) applyPricing(inv, p);
    }
  }

  private LotPricing readLegacyPricing(String inventoryId) {
    Object idQuery = isValidObjectId(inventoryId) ? new ObjectId(inventoryId) : inventoryId;
    Document doc = mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(idQuery)),
        Document.class,
        "inventory");
    if (doc == null) {
      log.debug("readLegacyPricing: no document found for inventoryId={}", inventoryId);
      return null;
    }
    return legacyPricingFrom(inventoryId, doc);
  }

  /** Legacy pricing for many lots in one query (price fields only), keyed by inventory id. */
  private Map<String, LotPricing> readLegacyPricingBatch(List<String> inventoryIds) {
    if (inventoryIds.isEmpty()) return Map.of();
    List<Object> ids = new ArrayList<>(inventoryIds.size());
    for (String id : inventoryIds) {
      ids.add(isValidObjectId(id) ? new ObjectId(id) : id);
    }
    Query query = Query.query(Criteria.where("_id").in(ids)).cursorBatchSize(ids.size()); // one reply, no getMore
    LEGACY_PRICING_FIELDS.forEach(f -> query.fields().include(f));
    Map<String, LotPricing> out = new HashMap<>();
    for (Document doc : mongoTemplate.find(query, Document.class, "inventory")) {
      String id = String.valueOf(doc.get("_id"));
      LotPricing p = legacyPricingFrom(id, doc);
      if (p != null) out.put(id, p);
    }
    return out;
  }

  /** Every field {@link #legacyPricingFrom} reads. */
  private static final List<String> LEGACY_PRICING_FIELDS = List.of(
      "maximumRetailPrice", "costPrice", "priceToRetail", "sellingPrice", "additionalDiscount", "sgst", "cgst",
      "schemeType", "schemePayFor", "schemeFree", "schemePercentage",
      "purchaseSchemeType", "purchaseSchemePayFor", "purchaseSchemeFree", "purchaseSchemePercentage");

  private LotPricing legacyPricingFrom(String inventoryId, Document doc) {
    BigDecimal mrp = getBigDecimal(doc, "maximumRetailPrice");
    BigDecimal cost = getBigDecimal(doc, "costPrice");
    BigDecimal ptr = getBigDecimal(doc, "priceToRetail");
    if (ptr == null) ptr = getBigDecimal(doc, "sellingPrice"); // backward compat
    BigDecimal discount = getBigDecimal(doc, "additionalDiscount");
    String sgst = toStringOrNull(doc.get("sgst"));
    String cgst = toStringOrNull(doc.get("cgst"));
    SchemeTerms saleScheme = buildLegacySaleScheme(doc);
    SchemeTerms purchaseScheme = buildLegacyPurchaseScheme(doc);
    log.debug("readLegacyPricing inventoryId={} docKeys={} sgst={} (raw={}) cgst={} (raw={})",
        inventoryId, doc.keySet(), sgst, doc.get("sgst"), cgst, doc.get("cgst"));
    if (mrp == null && cost == null && ptr == null && discount == null && sgst == null && cgst == null
        && saleScheme == null && purchaseScheme == null) {
      return null;
    }
    return new LotPricing(mrp, cost, null, ptr, null, null, ptr, discount, null, purchaseScheme, saleScheme, sgst, cgst, null);
  }

  private void applyPricing(Inventory inv, LotPricing p) {
    inv.setMaximumRetailPrice(p.maximumRetailPrice());
    inv.setCostPrice(p.costPrice());
    inv.setEffectiveCostPrice(p.resolveEffectiveCostPrice());
    inv.setRates(p.rates());
    inv.setDefaultRate(StringUtils.hasText(p.defaultRate())
        ? p.defaultRate()
        : (p.priceToRetail() != null ? "priceToRetail" : null));
    inv.setPriceToRetail(p.priceToRetail());
    inv.setSellingPrice(p.effectivePrice());
    inv.setSaleAdditionalDiscount(p.saleAdditionalDiscount());
    inv.setPurchaseAdditionalDiscount(p.purchaseAdditionalDiscount());
    if (p.saleScheme() != null) {
      var ss = p.saleScheme();
      if (StringUtils.hasText(ss.schemeType())) {
        try {
          inv.setSchemeType(SchemeType.valueOf(ss.schemeType()));
        } catch (IllegalArgumentException ignored) { /* keep null */ }
      }
      inv.setSchemePayFor(ss.schemePayFor());
      inv.setSchemeFree(ss.schemeFree());
      inv.setSchemePercentage(ss.schemePercentage());
    }
    if (p.purchaseScheme() != null) {
      var ps = p.purchaseScheme();
      if (StringUtils.hasText(ps.schemeType())) {
        try {
          inv.setPurchaseSchemeType(SchemeType.valueOf(ps.schemeType()));
        } catch (IllegalArgumentException ignored) { /* keep null */ }
      }
      inv.setPurchaseSchemePayFor(ps.schemePayFor());
      inv.setPurchaseSchemeFree(ps.schemeFree());
      inv.setPurchaseSchemePercentage(ps.schemePercentage());
    }
    if (inv.getBillingMode() == BillingMode.BASIC) {
      inv.setSgst(null);
      inv.setCgst(null);
      return;
    }
    // GST comes from the lot's own pricing only; the shop carries no GST rate.
    inv.setSgst(p.sgst());
    inv.setCgst(p.cgst());
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

  private static SchemeTerms buildLegacySaleScheme(Document doc) {
    String schemeType = toStringOrNull(doc.get("schemeType"));
    Integer payFor = getInt(doc, "schemePayFor");
    Integer free = getInt(doc, "schemeFree");
    BigDecimal pct = getBigDecimal(doc, "schemePercentage");
    if (schemeType == null && payFor == null && free == null && pct == null) return null;
    return new SchemeTerms(schemeType, payFor, free, pct);
  }

  private static SchemeTerms buildLegacyPurchaseScheme(Document doc) {
    String schemeType = toStringOrNull(doc.get("purchaseSchemeType"));
    Integer payFor = getInt(doc, "purchaseSchemePayFor");
    Integer free = getInt(doc, "purchaseSchemeFree");
    BigDecimal pct = getBigDecimal(doc, "purchaseSchemePercentage");
    if (schemeType == null && payFor == null && free == null && pct == null) return null;
    return new SchemeTerms(schemeType, payFor, free, pct);
  }

  private static Integer getInt(Document doc, String key) {
    Object v = doc.get(key);
    if (v == null) return null;
    if (v instanceof Integer) return (Integer) v;
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      return Integer.parseInt(v.toString());
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
