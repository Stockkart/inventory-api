package com.inventory.product.domain.model.pricing;

import com.inventory.pricing.api.InventoryPricingAdapter;
import com.inventory.pricing.api.dto.PricingCreateCommand;
import com.inventory.pricing.api.dto.PricingUpdateCommand;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Handles inventory pricing write: persist pricing on create/update.
 */
@Slf4j
@Component
public class InventoryPricingWriteHandler {

  @Autowired
  private InventoryPricingAdapter pricingPort;

  @Autowired
  private ShopRepository shopRepository;

  public void persistOnSave(Inventory inventory) {
    try {
      if (inventory.getId() == null && hasPricingData(inventory) && StringUtils.hasText(inventory.getShopId())) {
        var cmd = PricingCreateCommand.builder()
            .shopId(inventory.getShopId())
            .maximumRetailPrice(inventory.getMaximumRetailPrice())
            .costPrice(inventory.getCostPrice())
            .sellingPrice(inventory.getSellingPrice())
            .rates(inventory.getRates())
            .defaultRate(inventory.getDefaultRate())
            .additionalDiscount(inventory.getAdditionalDiscount())
            .sgst(resolveSgst(inventory.getSgst(), inventory.getShopId()))
            .cgst(resolveCgst(inventory.getCgst(), inventory.getShopId()))
            .build();
        String pricingId = pricingPort.create(cmd);
        inventory.setPricingId(pricingId);
        return;
      }
      InventoryPricingContext.Context ctx = InventoryPricingContext.get();
      if (ctx != null && ctx.type == InventoryPricingContext.Type.UPDATE && ctx.updateRequest != null) {
        UpdateInventoryRequest req = ctx.updateRequest;
        if (req.getAdditionalDiscount() != null && StringUtils.hasText(inventory.getPricingId())) {
          pricingPort.update(inventory.getPricingId(), new PricingUpdateCommand(req.getAdditionalDiscount()));
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
