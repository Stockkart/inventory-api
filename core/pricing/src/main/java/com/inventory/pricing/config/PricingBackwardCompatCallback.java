package com.inventory.pricing.config;

import com.inventory.pricing.domain.model.Pricing;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Backward compatibility: when a pricing document has {@code sellingPrice} but not {@code priceToRetail},
 * copy sellingPrice into priceToRetail so the API always returns priceToRetail.
 */
@Component
public class PricingBackwardCompatCallback implements AfterConvertCallback<Pricing>, Ordered {

  @Override
  public Pricing onAfterConvert(Pricing entity, Document document, String collection) {
    if (entity.getPriceToRetail() == null) {
      BigDecimal sellingPrice = getBigDecimal(document, "sellingPrice");
      if (sellingPrice != null) {
        entity.setPriceToRetail(sellingPrice);
      }
    }
    if (entity.getSellingPrice() == null && entity.getPriceToRetail() != null) {
      entity.setSellingPrice(entity.getPriceToRetail());
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
