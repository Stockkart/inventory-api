package com.inventory.product.service.vertical;

import com.inventory.pluginengine.cart.CartLineSnapshot;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.util.PurchaseItemRefs;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class CartLineSnapshotMapper {

  private final PurchaseMapper purchaseMapper;

  public CartLineSnapshotMapper(PurchaseMapper purchaseMapper) {
    this.purchaseMapper = purchaseMapper;
  }

  public PurchaseItem toPurchaseItem(CartLineSnapshot snapshot) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef(snapshot.getSellableRef());
    item.setStockRef(snapshot.getStockRef());
    item.setSellMode(sellModeLabel(snapshot));
    item.setName(snapshot.getName());
    item.setBillingMode(
        snapshot.getBillingMode() != null
            ? BillingMode.valueOf(snapshot.getBillingMode())
            : BillingMode.REGULAR);
    item.setQuantity(snapshot.getQuantity());
    item.setSaleUnit(snapshot.getSaleUnit());
    item.setBaseUnit(snapshot.getBaseUnit());
    item.setPackUnitUqc(snapshot.getPackUnitUqc());
    item.setBaseQuantity(snapshot.getBaseQuantity());
    item.setUnitFactor(snapshot.getUnitFactor() != null ? snapshot.getUnitFactor() : 1);
    item.setMaximumRetailPrice(snapshot.getMaximumRetailPrice());
    item.setPriceToRetail(snapshot.getPriceToRetail());
    item.setDiscount(snapshot.getDiscount() != null ? snapshot.getDiscount() : BigDecimal.ZERO);
    item.setSaleAdditionalDiscount(snapshot.getSaleAdditionalDiscount());
    item.setTotalAmount(snapshot.getTotalAmount());
    item.setSgst(snapshot.getSgst());
    item.setCgst(snapshot.getCgst());
    item.setCostPrice(snapshot.getCostPrice());
    item.setCostTotal(snapshot.getCostTotal());
    purchaseMapper.enrichPurchaseItemMargin(item);
    PurchaseItemRefs.normalize(item);
    return item;
  }

  private static String sellModeLabel(CartLineSnapshot snapshot) {
    if (snapshot.getSellMode() == null) {
      return "sku";
    }
    return snapshot.getSellMode().name().toLowerCase();
  }
}
