package com.inventory.product.util;

import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads embedded line arrays from raw Mongo documents when nested types did not deserialize
 * into entity fields (e.g. BSON key/name drift or legacy shapes).
 */
public final class MongoEmbeddedReadUtil {

  private MongoEmbeddedReadUtil() {}

  public static Map<String, Document> documentsByShopAndIds(
      MongoTemplate mongoTemplate,
      String collection,
      String shopId,
      Collection<String> ids) {
    if (mongoTemplate == null
        || !StringUtils.hasText(collection)
        || !StringUtils.hasText(shopId)
        || ids == null
        || ids.isEmpty()) {
      return Map.of();
    }

    Query q =
        Query.query(Criteria.where("_id").in(ids).and("shopId").is(shopId.trim()));
    List<Document> docs = mongoTemplate.find(q, Document.class, collection);
    Map<String, Document> out = new HashMap<>(docs.size());
    for (Document d : docs) {
      if (d == null) {
        continue;
      }
      Object oid = d.get("_id");
      if (oid != null) {
        out.put(oid.toString(), d);
      }
    }
    return out;
  }

  public static List<VendorPurchaseReturnItem> vendorReturnLineItems(Document raw) {
    List<Document> embedded =
        embeddedDocList(raw, "items", "returnItems", "return_items", "lines");
    if (embedded.isEmpty()) {
      return List.of();
    }
    List<VendorPurchaseReturnItem> list = new ArrayList<>(embedded.size());
    for (Document d : embedded) {
      VendorPurchaseReturnItem it = toVendorPurchaseReturnItem(d);
      if (it != null) {
        list.add(it);
      }
    }
    return list;
  }

  public static List<RefundItem> refundLineItems(Document raw) {
    List<Document> embedded =
        embeddedDocList(raw, "refundedItems", "refunded_items", "items", "lines");
    if (embedded.isEmpty()) {
      return List.of();
    }
    List<RefundItem> list = new ArrayList<>(embedded.size());
    for (Document d : embedded) {
      RefundItem ri = toRefundItem(d);
      if (ri != null) {
        list.add(ri);
      }
    }
    return list;
  }

  private static List<Document> embeddedDocList(Document raw, String... keys) {
    if (raw == null) {
      return List.of();
    }
    for (String k : keys) {
      Object arr = raw.get(k);
      if (!(arr instanceof List<?> list)) {
        continue;
      }
      List<Document> out = new ArrayList<>();
      for (Object o : list) {
        if (o instanceof Document doc) {
          out.add(doc);
        } else if (o instanceof Map<?, ?> m) {
          Document row = new Document();
          for (Map.Entry<?, ?> e : m.entrySet()) {
            row.append(String.valueOf(e.getKey()), e.getValue());
          }
          out.add(row);
        }
      }
      if (!out.isEmpty()) {
        return out;
      }
    }
    return List.of();
  }

  private static VendorPurchaseReturnItem toVendorPurchaseReturnItem(Document d) {
    String inventoryId =
        firstNonEmptyString(d, "inventoryId", "inventory_id", "inventoryID", "lotId", "lot_id");
    if (!StringUtils.hasText(inventoryId)) {
      return null;
    }
    VendorPurchaseReturnItem it = new VendorPurchaseReturnItem();
    it.setInventoryId(inventoryId.trim());
    it.setBaseQuantityReturned(firstInt(d, "baseQuantityReturned", "base_quantity_returned"));
    it.setTaxableValue(firstBd(d, "taxableValue", "taxable_value"));
    it.setCentralTaxAmount(
        firstBd(d, "centralTaxAmount", "central_tax_amount", "cgstAmount", "centralGstAmount"));
    it.setStateUtTaxAmount(
        firstBd(d, "stateUtTaxAmount", "state_ut_tax_amount", "sgstAmount", "stateGstAmount"));
    it.setLineNoteValue(firstBd(d, "lineNoteValue", "line_note_value"));
    return it;
  }

  private static RefundItem toRefundItem(Document d) {
    String inventoryId = firstNonEmptyString(d, "inventoryId", "inventory_id", "lotId", "lot_id");
    if (!StringUtils.hasText(inventoryId)) {
      return null;
    }
    RefundItem ri = new RefundItem();
    ri.setInventoryId(inventoryId.trim());
    ri.setName(firstNonEmptyString(d, "name", "productName", "product_name"));
    ri.setQuantity(firstInt(d, "quantity", "qty"));
    ri.setPriceToRetail(firstBd(d, "priceToRetail", "price_to_retail", "unitPrice", "price"));
    ri.setItemRefundAmount(
        firstBd(d, "itemRefundAmount", "item_refund_amount", "lineTotal", "line_total"));
    return ri;
  }

  private static String firstNonEmptyString(Document d, String... keys) {
    if (d == null) {
      return null;
    }
    for (String k : keys) {
      Object v = d.get(k);
      if (v == null) {
        continue;
      }
      String s = v.toString().trim();
      if (StringUtils.hasText(s)) {
        return s;
      }
    }
    return null;
  }

  private static Integer firstInt(Document d, String... keys) {
    for (String k : keys) {
      Integer v = coerceInt(d != null ? d.get(k) : null);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private static BigDecimal firstBd(Document d, String... keys) {
    for (String k : keys) {
      BigDecimal v = coerceBd(d != null ? d.get(k) : null);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private static Integer coerceInt(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Integer i) {
      return i;
    }
    if (o instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(o.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static BigDecimal coerceBd(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof BigDecimal bd) {
      return bd;
    }
    if (o instanceof Decimal128 d128) {
      return d128.bigDecimalValue();
    }
    if (o instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    try {
      String s = o.toString().trim();
      if (!StringUtils.hasText(s)) {
        return null;
      }
      return new BigDecimal(s);
    } catch (Exception e) {
      return null;
    }
  }
}
