package com.inventory.product.domain.model.pricing;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.pricing.service.ShopRateRulesPort;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.pricing.utils.constants.PricingConstants;
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

  @Autowired(required = false)
  private ShopRateRulesPort shopRateRulesPort;

  public void persistOnSave(Inventory inventory) {
    try {
      String verticalId = resolveVerticalId(inventory.getShopId());
      if (inventory.getId() == null && hasPricingData(inventory) && StringUtils.hasText(inventory.getShopId())) {
        var builder = PricingCreateCommand.builder()
            .shopId(inventory.getShopId())
            .verticalId(verticalId)
            .costPrice(inventory.getCostPrice())
            .saleAdditionalDiscount(inventory.getSaleAdditionalDiscount())
            .purchaseAdditionalDiscount(inventory.getPurchaseAdditionalDiscount())
            .costPriceIncludesTax(inventory.getCostPriceIncludesTax())
            .sgst(resolveSgst(inventory.getSgst()))
            .cgst(resolveCgst(inventory.getCgst()));
        if (isRetailerShop(inventory.getShopId())) {
          // Retail: shop enters Rate (cost) + Selling Price; MRP/PTR mirror selling price.
          // Schemes / discounts still persist (unlike cafe simplePricing).
          java.math.BigDecimal selling = inventory.getSellingPrice();
          builder
              .sellingPrice(selling)
              .maximumRetailPrice(selling)
              .priceToRetail(selling)
              .defaultRate("priceToRetail")
              // With rate rules the generated rates stay; PricingService rebuilds them from the
              // selling price. Without rules a retailer has none.
              .rates(hasRateRules(inventory.getShopId()) ? inventory.getRates() : null)
              .purchaseScheme(buildPurchaseScheme(inventory))
              .saleScheme(buildSaleScheme(inventory));
        } else {
          builder
              .maximumRetailPrice(inventory.getMaximumRetailPrice())
              .priceToRetail(inventory.getPriceToRetail())
              .sellingPrice(inventory.getSellingPrice())
              .rates(inventory.getRates())
              .defaultRate(resolveDefaultRateForCreate(inventory, verticalId))
              .purchaseScheme(buildPurchaseScheme(inventory))
              .saleScheme(buildSaleScheme(inventory));
        }
        String pricingId = pricingPort.create(builder.build());
        inventory.setPricingId(pricingId);
        return;
      }
      InventoryPricingContext.Context ctx = InventoryPricingContext.get();
      if (ctx != null && ctx.type == InventoryPricingContext.Type.UPDATE && ctx.updateRequest != null) {
        UpdateInventoryRequest req = ctx.updateRequest;
        if (hasAnyPricingUpdate(req) && StringUtils.hasText(inventory.getPricingId())) {
          java.math.BigDecimal mrp = req.getMaximumRetailPrice();
          java.math.BigDecimal ptr = req.getPriceToRetail();
          // A retailer with rate rules: MRP and PTR mirror a selling price sent alone, as on
          // create, so rules on the selling price and the at-MRP check see one figure. Shops
          // without rules keep today's edit behaviour.
          if (req.getSellingPrice() != null && mrp == null && ptr == null
              && isRetailerShop(inventory.getShopId()) && hasRateRules(inventory.getShopId())) {
            mrp = req.getSellingPrice();
            ptr = req.getSellingPrice();
          }
          var cmd = PricingUpdateCommand.builder()
              .verticalId(verticalId)
              .maximumRetailPrice(mrp)
              .costPrice(req.getCostPrice())
              .priceToRetail(ptr)
              .sellingPrice(req.getSellingPrice())
              .rates(req.getRates())
              .defaultRate(req.getDefaultRate())
              .saleAdditionalDiscount(req.getSaleAdditionalDiscount())
              .purchaseAdditionalDiscount(req.getPurchaseAdditionalDiscount())
              .purchaseScheme(buildPurchaseSchemeFromRequest(req))
              .saleScheme(buildSaleSchemeFromRequest(req))
              .sgst(req.getSgst())
              .cgst(req.getCgst())
              .clearFields(pricingClearFields(req))
              .build();
          pricingPort.update(inventory.getPricingId(), cmd, ctx.userId);
        }
      }
    } catch (ValidationException e) {
      // A rejected rate edit (a case-only clash with a rule, an override on a missing base) must
      // reach the caller as 400 before the inventory is saved, not drop the pricing update.
      throw e;
    } catch (BaseException e) {
      // A denied rate edit must reach the caller (403), not save the inventory without its pricing.
      if (e.getErrorCode() == ErrorCode.ACCESS_DENIED) {
        throw e;
      }
      log.warn("Failed to persist pricing for inventory {}: {}", inventory.getId(), e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to persist pricing for inventory {}: {}", inventory.getId(), e.getMessage());
    }
  }

  private boolean hasRateRules(String shopId) {
    return shopRateRulesPort != null && !shopRateRulesPort.rulesFor(shopId).isEmpty();
  }

  private boolean isRetailerShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return false;
    }
    return shopRepository.findById(shopId)
        .map(s -> s.getShopType() == ShopType.RETAILER)
        .orElse(false);
  }

  /** For retailer shops, default price is MRP (tax-inclusive). Cafe uses simple pricing without defaultRate. */
  private String resolveDefaultRateForCreate(Inventory inv, String verticalId) {
    if ("cafe".equalsIgnoreCase(verticalId)) {
      return null;
    }
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
        || req.getSellingPrice() != null
        || StringUtils.hasText(req.getSgst()) || StringUtils.hasText(req.getCgst())
        || pricingClearFields(req) != null;
  }

  /** The request's clearFields that name pricing fields, or null when there are none. */
  private java.util.Set<String> pricingClearFields(UpdateInventoryRequest req) {
    if (req.getClearFields() == null) {
      return null;
    }
    java.util.Set<String> pricing = new java.util.LinkedHashSet<>();
    for (String field : req.getClearFields()) {
      if (PricingConstants.CLEARABLE_FIELDS.contains(field)) {
        pricing.add(field);
      }
    }
    return pricing.isEmpty() ? null : pricing;
  }

  private boolean hasPricingData(Inventory inv) {
    return inv.getMaximumRetailPrice() != null || inv.getCostPrice() != null
        || inv.getPriceToRetail() != null || inv.getSellingPrice() != null
        || inv.getSaleAdditionalDiscount() != null
        || (inv.getRates() != null && !inv.getRates().isEmpty())
        || inv.getSchemeType() != null || inv.getSchemePayFor() != null
        || inv.getSchemeFree() != null || inv.getSchemePercentage() != null
        || inv.getPurchaseSchemeType() != null || inv.getPurchaseSchemePayFor() != null
        || inv.getPurchaseSchemeFree() != null || inv.getPurchaseSchemePercentage() != null;
  }

  private String resolveSgst(String fromRequest) {
    InventoryPricingContext.Context ctx = InventoryPricingContext.get();
    if (ctx != null && ctx.type == InventoryPricingContext.Type.CREATE
        && ctx.createRequest != null && ctx.createRequest.getBillingMode() == BillingMode.BASIC) {
      return null;
    }
    // GST is the lot's own; there is no shop-level default (the validator requires it on create).
    return StringUtils.hasText(fromRequest) ? fromRequest : null;
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

  private String resolveVerticalId(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return null;
    }
    return shopRepository.findById(shopId).map(Shop::getVerticalId).orElse(null);
  }

  private String resolveCgst(String fromRequest) {
    InventoryPricingContext.Context ctx = InventoryPricingContext.get();
    if (ctx != null && ctx.type == InventoryPricingContext.Type.CREATE
        && ctx.createRequest != null && ctx.createRequest.getBillingMode() == BillingMode.BASIC) {
      return null;
    }
    // GST is the lot's own; there is no shop-level default (the validator requires it on create).
    return StringUtils.hasText(fromRequest) ? fromRequest : null;
  }
}
