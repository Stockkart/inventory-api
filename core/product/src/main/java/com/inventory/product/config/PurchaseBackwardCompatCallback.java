package com.inventory.product.config;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backward compatibility: when a purchase item has {@code sellingPrice} but not {@code priceToRetail},
 * copy sellingPrice into priceToRetail so the API always returns priceToRetail.
 */
@Component
public class PurchaseBackwardCompatCallback implements AfterConvertCallback<Purchase>, Ordered {

  @Override
  public Purchase onAfterConvert(Purchase entity, Document document, String collection) {
    List<PurchaseItem> items = entity.getItems();
    if (items == null || items.isEmpty()) {
      return entity;
    }
    Object rawItems = document.get("items");
    if (!(rawItems instanceof List<?> rawList)) {
      return entity;
    }
    for (int i = 0; i < items.size() && i < rawList.size(); i++) {
      PurchaseItem item = items.get(i);
      if (item.getPriceToRetail() != null) {
        continue;
      }
      Object raw = rawList.get(i);
      if (raw instanceof Document itemDoc) {
        BigDecimal sellingPrice = getBigDecimal(itemDoc, "sellingPrice");
        if (sellingPrice != null) {
          item.setPriceToRetail(sellingPrice);
        }
      }
    }
    return entity;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
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
}
