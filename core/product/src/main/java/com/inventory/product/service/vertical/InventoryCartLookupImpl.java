package com.inventory.product.service.vertical;

import com.inventory.pluginengine.integration.InventoryCartLookup;
import com.inventory.pluginengine.integration.InventoryLineSnapshot;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class InventoryCartLookupImpl implements InventoryCartLookup {

  private final InventoryRepository inventoryRepository;

  public InventoryCartLookupImpl(InventoryRepository inventoryRepository) {
    this.inventoryRepository = inventoryRepository;
  }

  @Override
  public Optional<InventoryLineSnapshot> findForShop(String shopId, String inventoryId) {
    return inventoryRepository
        .findById(inventoryId)
        .filter(inv -> shopId.equals(inv.getShopId()))
        .map(this::toSnapshot);
  }

  private InventoryLineSnapshot toSnapshot(Inventory inv) {
  int baseCount =
      inv.getCurrentBaseCount() != null
          ? inv.getCurrentBaseCount()
          : (inv.getCurrentCount() != null ? inv.getCurrentCount().intValue() : 0);
    BigDecimal selling =
        inv.getSellingPrice() != null ? inv.getSellingPrice() : inv.getPriceToRetail();
    return InventoryLineSnapshot.builder()
        .inventoryId(inv.getId())
        .name(inv.getName())
        .maximumRetailPrice(inv.getMaximumRetailPrice())
        .priceToRetail(selling)
        .costPrice(inv.getCostPrice())
        .cgst(inv.getCgst())
        .sgst(inv.getSgst())
        .availableBaseCount(baseCount)
        .build();
  }
}
