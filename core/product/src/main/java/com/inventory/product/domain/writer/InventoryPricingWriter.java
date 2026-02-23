package com.inventory.product.domain.writer;

import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import com.inventory.pricing.service.PricingService;
import com.inventory.product.domain.context.InventoryPricingContext;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Writes pricing to the Pricing module. Used by AOP aspect on inventory save.
 * Reads from InventoryPricingContext (set by aspect on InventoryService); no service changes needed.
 */
@Slf4j
@Component
public class InventoryPricingWriter {

  @Autowired
  private PricingService pricingService;

  @Autowired
  private ShopRepository shopRepository;

  /**
   * Process pricing from context before inventory is persisted.
   * For CREATE: creates pricing, sets pricingId on inventory.
   * For UPDATE: updates pricing by inventory's pricingId.
   */
  public void processFromContext(Inventory inventory) {
    InventoryPricingContext.Context ctx = InventoryPricingContext.get();
    if (ctx == null) return;

    try {
      if (ctx.type == InventoryPricingContext.Type.CREATE && ctx.createRequest != null) {
        CreateInventoryRequest req = ctx.createRequest;
        String resolvedSgst = resolveSgst(req.getSgst(), ctx.shopId);
        String resolvedCgst = resolveCgst(req.getCgst(), ctx.shopId);

        CreatePricingRequest pricingReq = new CreatePricingRequest();
        pricingReq.setShopId(ctx.shopId);
        pricingReq.setMaximumRetailPrice(req.getMaximumRetailPrice());
        pricingReq.setCostPrice(req.getCostPrice());
        pricingReq.setSellingPrice(req.getSellingPrice());
        pricingReq.setAdditionalDiscount(req.getAdditionalDiscount());
        pricingReq.setSgst(resolvedSgst);
        pricingReq.setCgst(resolvedCgst);

        var pricing = pricingService.createAndReturnEntity(pricingReq);
        inventory.setPricingId(pricing.getId());
      } else if (ctx.type == InventoryPricingContext.Type.UPDATE && ctx.updateRequest != null) {
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
