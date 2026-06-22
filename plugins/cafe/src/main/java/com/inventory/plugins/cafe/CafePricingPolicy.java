package com.inventory.plugins.cafe;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.pricing.PricingPolicyContext;
import com.inventory.pluginengine.pricing.PricingValidationSupport;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Cafe ingredient pricing: cost at stock-in; optional reference selling price.
 * No PTR, MRP, rates, defaultRate, or schemes on the pricing record.
 */
@Component
public class CafePricingPolicy implements VerticalPricingPolicy {

  @Override
  public void validateCreate(PricingPolicyContext context) {
    if (context == null) {
      throw new ValidationException("Create pricing request cannot be null");
    }
    rejectRetailFields(context);
    if (context.getCostPrice() == null) {
      throw new ValidationException("Cost price is required");
    }
    if (context.getCostPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Cost price must be greater than 0");
    }
    if (context.getSellingPrice() != null
        && context.getSellingPrice().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Selling price cannot be negative");
    }
    PricingValidationSupport.validateGstRates(context.getSgst(), context.getCgst());
  }

  @Override
  public void validateUpdate(PricingPolicyContext existing, PricingPolicyContext patch) {
    if (patch == null) {
      return;
    }
    rejectRetailFields(patch);
    if (patch.getCostPrice() != null && patch.getCostPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Cost price must be greater than 0");
    }
    if (patch.getSellingPrice() != null
        && patch.getSellingPrice().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Selling price cannot be negative");
    }
    PricingValidationSupport.validateGstRates(patch.getSgst(), patch.getCgst());
  }

  @Override
  public PricingPolicyContext normalizeOnCreate(PricingPolicyContext context) {
    if (context == null) {
      return null;
    }
    context.setMaximumRetailPrice(null);
    context.setPriceToRetail(null);
    context.setRates(null);
    context.setDefaultRate(null);
    context.setSaleAdditionalDiscount(null);
    context.setPurchaseAdditionalDiscount(null);
    context.setPurchaseScheme(null);
    context.setSaleScheme(null);
    return context;
  }

  @Override
  public PricingPolicyContext normalizeOnUpdate(PricingPolicyContext existing, PricingPolicyContext patch) {
    if (patch == null) {
      return null;
    }
    patch.setMaximumRetailPrice(null);
    patch.setPriceToRetail(null);
    patch.setRates(null);
    patch.setDefaultRate(null);
    patch.setSaleAdditionalDiscount(null);
    patch.setPurchaseAdditionalDiscount(null);
    patch.setPurchaseScheme(null);
    patch.setSaleScheme(null);
    return patch;
  }

  private void rejectRetailFields(PricingPolicyContext context) {
    if (isPositive(context.getMaximumRetailPrice())) {
      throw new ValidationException("MRP is not used for cafe ingredient pricing");
    }
    if (isPositive(context.getPriceToRetail())) {
      throw new ValidationException("PTR is not used for cafe ingredient pricing");
    }
    if (context.getRates() != null && !context.getRates().isEmpty()) {
      throw new ValidationException("Custom rates are not used for cafe ingredient pricing");
    }
    if (context.getDefaultRate() != null && !context.getDefaultRate().isBlank()) {
      throw new ValidationException("defaultRate is not used for cafe ingredient pricing");
    }
    if (context.getSaleScheme() != null && !context.getSaleScheme().isEmpty()) {
      throw new ValidationException("Sale schemes are not used for cafe ingredient pricing");
    }
    if (context.getPurchaseScheme() != null && !context.getPurchaseScheme().isEmpty()) {
      throw new ValidationException("Purchase schemes are not used on cafe pricing records");
    }
    if (context.getSaleAdditionalDiscount() != null
        && context.getSaleAdditionalDiscount().compareTo(BigDecimal.ZERO) > 0) {
      throw new ValidationException("Additional discount is not used for cafe ingredient pricing");
    }
  }

  private static boolean isPositive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }
}
