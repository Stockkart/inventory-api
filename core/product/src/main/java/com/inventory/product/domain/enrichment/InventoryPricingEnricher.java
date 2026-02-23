package com.inventory.product.domain.enrichment;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.PricingData;
import com.inventory.product.domain.resolver.InventoryPricingResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enriches Inventory entities with pricing data from the Pricing module.
 * Used by the AOP aspect after repository reads.
 */
@Slf4j
@Component
public class InventoryPricingEnricher {

  @Autowired
  private InventoryPricingResolver inventoryPricingResolver;

  /**
   * Enrich a single inventory with pricing (from Pricing module or legacy fallback).
   */
  public void enrich(Inventory inventory) {
    if (inventory == null) return;
    PricingData pricing = inventoryPricingResolver.resolvePricingInfo(inventory);
    if (pricing != null) {
      inventory.setMaximumRetailPrice(pricing.getMaximumRetailPrice());
      inventory.setCostPrice(pricing.getCostPrice());
      inventory.setSellingPrice(pricing.getSellingPrice());
      inventory.setAdditionalDiscount(pricing.getAdditionalDiscount());
      inventory.setSgst(pricing.getSgst());
      inventory.setCgst(pricing.getCgst());
    }
  }

  /**
   * Enrich a list of inventories with pricing, using batch fetch for efficiency.
   */
  public void enrich(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) return;
    java.util.Map<String, PricingData> pricingMap = inventoryPricingResolver.resolvePricingInfoBatch(inventories);
    for (Inventory inv : inventories) {
      PricingData pricing = pricingMap.get(inv.getId());
      if (pricing != null) {
        inv.setMaximumRetailPrice(pricing.getMaximumRetailPrice());
        inv.setCostPrice(pricing.getCostPrice());
        inv.setSellingPrice(pricing.getSellingPrice());
        inv.setAdditionalDiscount(pricing.getAdditionalDiscount());
        inv.setSgst(pricing.getSgst());
        inv.setCgst(pricing.getCgst());
      }
    }
  }
}
