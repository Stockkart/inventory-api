package com.inventory.pluginengine.pricing.defaultprovider;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.pricing.PricingPolicyContext;
import com.inventory.pluginengine.pricing.PricingValidationSupport;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Default retail pricing (medical, sports, …): MRP, PTR, rates, defaultRate, schemes.
 */
@Component
public class DefaultRetailPricingPolicy implements VerticalPricingPolicy {

  public static final String DEFAULT_RATE_PRICE_TO_RETAIL = "priceToRetail";

  @Override
  public void validateCreate(PricingPolicyContext context) {
    if (context == null) {
      throw new ValidationException("Create pricing request cannot be null");
    }
    PricingValidationSupport.validateRates(context.getRates(), context.getDefaultRate());
    BigDecimal effectiveSp =
        PricingValidationSupport.resolveEffectivePrice(
            context.getMaximumRetailPrice(),
            context.getCostPrice(),
            context.getPriceToRetail(),
            context.getSellingPrice(),
            context.getRates(),
            context.getDefaultRate());
    if (effectiveSp == null) {
      throw new ValidationException("Either priceToRetail or (rates with defaultRate) is required");
    }
    PricingValidationSupport.validatePriceValues(
        context.getMaximumRetailPrice(),
        context.getCostPrice(),
        effectiveSp,
        context.getSaleAdditionalDiscount());
    PricingValidationSupport.validateGstRates(context.getSgst(), context.getCgst());
  }

  @Override
  public void validateUpdate(PricingPolicyContext existing, PricingPolicyContext patch) {
    if (patch == null) {
      return;
    }
    PricingValidationSupport.validateRates(patch.getRates(), patch.getDefaultRate());
    PricingValidationSupport.validateGstRates(patch.getSgst(), patch.getCgst());
    if (patch.getMaximumRetailPrice() != null
        || patch.getCostPrice() != null
        || patch.getPriceToRetail() != null
        || patch.getSaleAdditionalDiscount() != null) {
      PricingValidationSupport.validatePriceValues(
          patch.getMaximumRetailPrice(),
          patch.getCostPrice(),
          patch.getPriceToRetail(),
          patch.getSaleAdditionalDiscount());
    }
  }

  @Override
  public PricingPolicyContext normalizeOnCreate(PricingPolicyContext context) {
    if (context == null) {
      return null;
    }
    if (!StringUtils.hasText(context.getDefaultRate())) {
      context.setDefaultRate(DEFAULT_RATE_PRICE_TO_RETAIL);
      context.setSellingPrice(context.getPriceToRetail());
    } else {
      context.setSellingPrice(
          PricingValidationSupport.resolveEffectivePrice(
              context.getMaximumRetailPrice(),
              context.getCostPrice(),
              context.getPriceToRetail(),
              context.getSellingPrice(),
              context.getRates(),
              context.getDefaultRate()));
    }
    return context;
  }

  @Override
  public PricingPolicyContext normalizeOnUpdate(PricingPolicyContext existing, PricingPolicyContext patch) {
    if (existing == null || patch == null) {
      return patch;
    }
    String defaultRate =
        StringUtils.hasText(patch.getDefaultRate())
            ? patch.getDefaultRate()
            : existing.getDefaultRate();
    PricingPolicyContext merged =
        PricingPolicyContext.builder()
            .shopId(existing.getShopId())
            .verticalId(existing.getVerticalId())
            .maximumRetailPrice(
                patch.getMaximumRetailPrice() != null
                    ? patch.getMaximumRetailPrice()
                    : existing.getMaximumRetailPrice())
            .costPrice(
                patch.getCostPrice() != null ? patch.getCostPrice() : existing.getCostPrice())
            .priceToRetail(
                patch.getPriceToRetail() != null
                    ? patch.getPriceToRetail()
                    : existing.getPriceToRetail())
            .sellingPrice(
                patch.getSellingPrice() != null
                    ? patch.getSellingPrice()
                    : existing.getSellingPrice())
            .rates(patch.getRates() != null ? patch.getRates() : existing.getRates())
            .defaultRate(defaultRate)
            .saleAdditionalDiscount(
                patch.getSaleAdditionalDiscount() != null
                    ? patch.getSaleAdditionalDiscount()
                    : existing.getSaleAdditionalDiscount())
            .purchaseAdditionalDiscount(
                patch.getPurchaseAdditionalDiscount() != null
                    ? patch.getPurchaseAdditionalDiscount()
                    : existing.getPurchaseAdditionalDiscount())
            .purchaseScheme(
                patch.getPurchaseScheme() != null
                    ? patch.getPurchaseScheme()
                    : existing.getPurchaseScheme())
            .saleScheme(
                patch.getSaleScheme() != null ? patch.getSaleScheme() : existing.getSaleScheme())
            .sgst(patch.getSgst() != null ? patch.getSgst() : existing.getSgst())
            .cgst(patch.getCgst() != null ? patch.getCgst() : existing.getCgst())
            .build();
    merged.setSellingPrice(
        PricingValidationSupport.resolveEffectivePrice(
            merged.getMaximumRetailPrice(),
            merged.getCostPrice(),
            merged.getPriceToRetail(),
            merged.getSellingPrice(),
            merged.getRates(),
            merged.getDefaultRate()));
    patch.setSellingPrice(merged.getSellingPrice());
    return patch;
  }
}
