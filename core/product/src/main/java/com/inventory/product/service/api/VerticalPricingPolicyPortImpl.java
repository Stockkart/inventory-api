package com.inventory.product.service.api;

import com.inventory.pluginengine.pricing.PricingPolicyContext;
import com.inventory.pluginengine.pricing.PricingRateEntry;
import com.inventory.pluginengine.pricing.PricingSchemeEntry;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import com.inventory.pricing.api.PricingDraft;
import com.inventory.pricing.api.RateView;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.port.VerticalPricingPolicyPort;
import com.inventory.product.service.vertical.PricingPolicyResolver;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * pricing's {@link VerticalPricingPolicyPort}: runs the vertical's plugin pricing policy on the
 * pricing handed over and hands the policy's result back.
 */
@Component
public class VerticalPricingPolicyPortImpl implements VerticalPricingPolicyPort {

  private final PricingPolicyResolver pricingPolicyResolver;

  public VerticalPricingPolicyPortImpl(PricingPolicyResolver pricingPolicyResolver) {
    this.pricingPolicyResolver = pricingPolicyResolver;
  }

  @Override
  public PricingDraft normalizeCreate(PricingDraft request) {
    VerticalPricingPolicy policy = pricingPolicyResolver.resolve(request.verticalId());
    PricingPolicyContext context = toContext(request);
    policy.validateCreate(context);
    return toDraft(policy.normalizeOnCreate(context));
  }

  @Override
  public PricingDraft normalizeUpdate(PricingDraft existing, PricingDraft changes) {
    VerticalPricingPolicy policy = pricingPolicyResolver.resolve(changes.verticalId());
    PricingPolicyContext existingContext = toContext(existing);
    PricingPolicyContext patchContext = toContext(changes);
    policy.validateUpdate(existingContext, patchContext);
    return toDraft(policy.normalizeOnUpdate(existingContext, patchContext));
  }

  @Override
  public PricingDraft normalizeStored(PricingDraft pricing) {
    VerticalPricingPolicy policy = pricingPolicyResolver.resolve(pricing.verticalId());
    return toDraft(policy.normalizeOnCreate(toContext(pricing)));
  }

  private static PricingPolicyContext toContext(PricingDraft d) {
    return PricingPolicyContext.builder()
        .shopId(d.shopId())
        .verticalId(d.verticalId())
        .maximumRetailPrice(d.maximumRetailPrice())
        .costPrice(d.costPrice())
        .priceToRetail(d.priceToRetail())
        .sellingPrice(d.sellingPrice())
        .rates(toRateEntries(d.rates()))
        .defaultRate(d.defaultRate())
        .saleAdditionalDiscount(d.saleAdditionalDiscount())
        .purchaseAdditionalDiscount(d.purchaseAdditionalDiscount())
        .purchaseScheme(toSchemeEntry(d.purchaseScheme()))
        .saleScheme(toSchemeEntry(d.saleScheme()))
        .sgst(d.sgst())
        .cgst(d.cgst())
        .build();
  }

  private static PricingDraft toDraft(PricingPolicyContext c) {
    return new PricingDraft(
        c.getShopId(),
        c.getVerticalId(),
        c.getMaximumRetailPrice(),
        c.getCostPrice(),
        c.getPriceToRetail(),
        c.getSellingPrice(),
        toRateViews(c.getRates()),
        c.getDefaultRate(),
        c.getSaleAdditionalDiscount(),
        c.getPurchaseAdditionalDiscount(),
        toTerms(c.getPurchaseScheme()),
        toTerms(c.getSaleScheme()),
        c.getSgst(),
        c.getCgst());
  }

  /** The policy sees no rates as null, as it always has. */
  private static List<PricingRateEntry> toRateEntries(List<RateView> rates) {
    if (rates == null || rates.isEmpty()) {
      return null;
    }
    return rates.stream()
        .map(r -> new PricingRateEntry(
            r.name(),
            r.price(),
            r.base(),
            r.baseValue(),
            r.discountPercent(),
            toSchemeEntry(r.scheme()),
            r.manual(),
            r.gstMode()))
        .toList();
  }

  private static List<RateView> toRateViews(List<PricingRateEntry> rates) {
    if (rates == null || rates.isEmpty()) {
      return null;
    }
    return rates.stream()
        .map(r -> new RateView(
            r.name(),
            r.price(),
            r.base(),
            r.baseValue(),
            r.discountPercent(),
            toTerms(r.scheme()),
            r.manual(),
            r.gstMode()))
        .toList();
  }

  private static PricingSchemeEntry toSchemeEntry(SchemeTerms s) {
    if (s == null) {
      return null;
    }
    return new PricingSchemeEntry(
        s.schemeType(), s.schemePayFor(), s.schemeFree(), s.schemePercentage());
  }

  /** An empty scheme from the policy means no scheme. */
  private static SchemeTerms toTerms(PricingSchemeEntry entry) {
    if (entry == null || entry.isEmpty()) {
      return null;
    }
    return new SchemeTerms(
        entry.schemeType(), entry.schemePayFor(), entry.schemeFree(), entry.schemePercentage());
  }
}
