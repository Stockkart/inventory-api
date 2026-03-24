package com.inventory.product.domain.model.pricing;

import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
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
        String defaultRate = resolveDefaultRateForCreate(inventory);
        var cmd = PricingCreateCommand.builder()
            .shopId(inventory.getShopId())
            .maximumRetailPrice(inventory.getMaximumRetailPrice())
            .costPrice(inventory.getCostPrice())
            .priceToRetail(inventory.getPriceToRetail())
            .rates(inventory.getRates())
            .defaultRate(defaultRate)
            .saleAdditionalDiscount(inventory.getSaleAdditionalDiscount())
            .purchaseAdditionalDiscount(inventory.getPurchaseAdditionalDiscount())
            .purchaseScheme(buildPurchaseScheme(inventory))
            .saleScheme(buildSaleScheme(inventory))
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
        if (hasAnyPricingUpdate(req) && StringUtils.hasText(inventory.getPricingId())) {
          var cmd = PricingUpdateCommand.builder()
              .maximumRetailPrice(req.getMaximumRetailPrice())
              .costPrice(req.getCostPrice())
              .priceToRetail(req.getPriceToRetail())
              .rates(req.getRates())
              .defaultRate(req.getDefaultRate())
              .saleAdditionalDiscount(req.getSaleAdditionalDiscount())
              .purchaseAdditionalDiscount(req.getPurchaseAdditionalDiscount())
              .purchaseScheme(buildPurchaseSchemeFromRequest(req))
              .saleScheme(buildSaleSchemeFromRequest(req))
              .sgst(req.getSgst())
              .cgst(req.getCgst())
              .build();
          pricingPort.update(inventory.getPricingId(), cmd);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to persist pricing for inventory {}: {}", inventory.getId(), e.getMessage());
    }
  }

  /** For retailer shops, default price is MRP (tax-inclusive). Otherwise use request/default (PTR). */
  private String resolveDefaultRateForCreate(Inventory inv) {
    if (StringUtils.hasText(inv.getDefaultRate())) {
      return inv.getDefaultRate();
    }
    if (!StringUtils.hasText(inv.getShopId())) {
      return null; // PricingService will use priceToRetail
    }
    return shopRepository.findById(inv.getShopId())
        .filter(s -> s.getShopType() == ShopType.RETAILER)
        .map(s -> "maximumRetailPrice")
        .orElse(null);
  }

  private boolean hasAnyPricingUpdate(UpdateInventoryRequest req) {
    return req.getMaximumRetailPrice() != null || req.getCostPrice() != null
        || req.getPriceToRetail() != null || req.getSaleAdditionalDiscount() != null
        || req.getPurchaseAdditionalDiscount() != null || req.getPurchaseSchemeType() != null
        || req.getPurchaseSchemePayFor() != null || req.getPurchaseSchemeFree() != null
        || req.getPurchaseSchemePercentage() != null
        || req.getSchemeType() != null || req.getSchemePayFor() != null
        || req.getSchemeFree() != null || req.getSchemePercentage() != null
        || (req.getRates() != null && !req.getRates().isEmpty())
        || StringUtils.hasText(req.getDefaultRate())
        || StringUtils.hasText(req.getSgst()) || StringUtils.hasText(req.getCgst());
  }

  private boolean hasPricingData(Inventory inv) {
    return inv.getMaximumRetailPrice() != null || inv.getCostPrice() != null
        || inv.getPriceToRetail() != null || inv.getSaleAdditionalDiscount() != null
        || (inv.getRates() != null && !inv.getRates().isEmpty())
        || inv.getSchemeType() != null || inv.getSchemePayFor() != null
        || inv.getSchemeFree() != null || inv.getSchemePercentage() != null
        || inv.getPurchaseSchemeType() != null || inv.getPurchaseSchemePayFor() != null
        || inv.getPurchaseSchemeFree() != null || inv.getPurchaseSchemePercentage() != null;
  }

  private String resolveSgst(String fromRequest, String shopId) {
    InventoryPricingContext.Context ctx = InventoryPricingContext.get();
    if (ctx != null && ctx.type == InventoryPricingContext.Type.CREATE
        && ctx.createRequest != null && ctx.createRequest.getBillingMode() == BillingMode.BASIC) {
      return null;
    }
    if (StringUtils.hasText(fromRequest)) return fromRequest;
    if (!StringUtils.hasText(shopId)) return null;
    return shopRepository.findById(shopId).map(Shop::getSgst).orElse(null);
  }

  private Scheme buildPurchaseSchemeFromRequest(UpdateInventoryRequest req) {
    SchemeType st = req.getPurchaseSchemeType();
    Integer payFor = req.getPurchaseSchemePayFor();
    Integer free = req.getPurchaseSchemeFree();
    java.math.BigDecimal pct = req.getPurchaseSchemePercentage();
    if (st == null && payFor == null && free == null && pct == null) {
      return null;
    }
    String schemeTypeStr = st != null ? st.name() : null;
    return new Scheme(schemeTypeStr, payFor, free, pct);
  }

  private Scheme buildPurchaseScheme(Inventory inv) {
    SchemeType st = inv.getPurchaseSchemeType();
    Integer payFor = inv.getPurchaseSchemePayFor();
    Integer free = inv.getPurchaseSchemeFree();
    java.math.BigDecimal pct = inv.getPurchaseSchemePercentage();
    if (st == null && payFor == null && free == null && pct == null) {
      return null;
    }
    String schemeTypeStr = st != null ? st.name() : null;
    return new Scheme(schemeTypeStr, payFor, free, pct);
  }

  private Scheme buildSaleScheme(Inventory inv) {
    SchemeType st = inv.getSchemeType();
    Integer payFor = inv.getSchemePayFor();
    Integer free = inv.getSchemeFree();
    java.math.BigDecimal pct = inv.getSchemePercentage();
    if (st == null && payFor == null && free == null && pct == null) {
      return null;
    }
    String schemeTypeStr = st != null ? st.name() : null;
    return new Scheme(schemeTypeStr, payFor, free, pct);
  }

  private Scheme buildSaleSchemeFromRequest(UpdateInventoryRequest req) {
    SchemeType st = req.getSchemeType();
    Integer payFor = req.getSchemePayFor();
    Integer free = req.getSchemeFree();
    java.math.BigDecimal pct = req.getSchemePercentage();
    if (st == null && payFor == null && free == null && pct == null) {
      return null;
    }
    String schemeTypeStr = st != null ? st.name() : null;
    return new Scheme(schemeTypeStr, payFor, free, pct);
  }

  private String resolveCgst(String fromRequest, String shopId) {
    InventoryPricingContext.Context ctx = InventoryPricingContext.get();
    if (ctx != null && ctx.type == InventoryPricingContext.Type.CREATE
        && ctx.createRequest != null && ctx.createRequest.getBillingMode() == BillingMode.BASIC) {
      return null;
    }
    if (StringUtils.hasText(fromRequest)) return fromRequest;
    if (!StringUtils.hasText(shopId)) return null;
    return shopRepository.findById(shopId).map(Shop::getCgst).orElse(null);
  }
}
