package com.inventory.pricing.service;

import com.inventory.pricing.api.PricingDraft;
import com.inventory.pricing.api.RateView;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.port.VerticalPricingPolicyPort;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies the vertical's pricing rules ({@link VerticalPricingPolicyPort}, implemented in product)
 * to pricing's own request and entity classes: copies what the rules read into a
 * {@link PricingDraft}, and writes back exactly the fields the rules may change.
 */
@Component
@RequiredArgsConstructor
public class VerticalPricingPolicies {

  private final VerticalPricingPolicyPort port;

  /** Validates a create request and normalises it in place. */
  public void validateAndNormalizeCreate(CreatePricingRequest request) {
    if (request == null) {
      return;
    }
    PricingDraft out = port.normalizeCreate(new PricingDraft(
        request.getShopId(),
        request.getVerticalId(),
        request.getMaximumRetailPrice(),
        request.getCostPrice(),
        request.getPriceToRetail(),
        request.getSellingPrice(),
        toViews(request.getRates()),
        request.getDefaultRate(),
        request.getSaleAdditionalDiscount(),
        request.getPurchaseAdditionalDiscount(),
        toTerms(request.getPurchaseScheme()),
        toTerms(request.getSaleScheme()),
        request.getSgst(),
        request.getCgst()));
    request.setMaximumRetailPrice(out.maximumRetailPrice());
    request.setCostPrice(out.costPrice());
    request.setPriceToRetail(out.priceToRetail());
    request.setSellingPrice(out.sellingPrice());
    request.setRates(toRates(out.rates()));
    request.setDefaultRate(out.defaultRate());
    request.setSaleAdditionalDiscount(out.saleAdditionalDiscount());
    request.setPurchaseAdditionalDiscount(out.purchaseAdditionalDiscount());
    request.setPurchaseScheme(toSchemeDto(out.purchaseScheme()));
    request.setSaleScheme(toSchemeDto(out.saleScheme()));
    request.setSgst(out.sgst());
    request.setCgst(out.cgst());
  }

  /**
   * Validates an update against the stored record and normalises it in place. Only the selling
   * price and the default rate are written back, and only when the rules give one.
   */
  public void validateAndNormalizeUpdate(
      UpdatePricingRequest request, String verticalId, Pricing existing) {
    if (request == null) {
      return;
    }
    PricingDraft changes = new PricingDraft(
        null,
        verticalId,
        request.getMaximumRetailPrice(),
        request.getCostPrice(),
        request.getPriceToRetail(),
        request.getSellingPrice(),
        toViews(request.getRates()),
        request.getDefaultRate(),
        request.getSaleAdditionalDiscount(),
        request.getPurchaseAdditionalDiscount(),
        toTerms(request.getPurchaseScheme()),
        toTerms(request.getSaleScheme()),
        request.getSgst(),
        request.getCgst());
    PricingDraft out = port.normalizeUpdate(toDraft(existing, verticalId), changes);
    if (out.sellingPrice() != null) {
      request.setSellingPrice(out.sellingPrice());
    }
    if (out.defaultRate() != null) {
      request.setDefaultRate(out.defaultRate());
    }
  }

  /** Normalises a record about to be stored, in place. GST rates are left as they are. */
  public void normalizeEntity(Pricing pricing, String verticalId) {
    if (pricing == null) {
      return;
    }
    PricingDraft out = port.normalizeStored(toDraft(pricing, verticalId));
    pricing.setMaximumRetailPrice(out.maximumRetailPrice());
    pricing.setCostPrice(out.costPrice());
    pricing.setPriceToRetail(out.priceToRetail());
    pricing.setSellingPrice(out.sellingPrice());
    pricing.setRates(toRates(out.rates()));
    pricing.setDefaultRate(out.defaultRate());
    pricing.setSaleAdditionalDiscount(out.saleAdditionalDiscount());
    pricing.setPurchaseAdditionalDiscount(out.purchaseAdditionalDiscount());
    pricing.setPurchaseScheme(toScheme(out.purchaseScheme()));
    pricing.setSaleScheme(toScheme(out.saleScheme()));
  }

  /** A stored record as the rules read it; null when there is none (a create has no record). */
  private static PricingDraft toDraft(Pricing p, String verticalId) {
    if (p == null) {
      return null;
    }
    return new PricingDraft(
        p.getShopId(),
        verticalId,
        p.getMaximumRetailPrice(),
        p.getCostPrice(),
        p.getPriceToRetail(),
        p.getSellingPrice(),
        toViews(p.getRates()),
        p.getDefaultRate(),
        p.getSaleAdditionalDiscount(),
        p.getPurchaseAdditionalDiscount(),
        toTerms(p.getPurchaseScheme()),
        toTerms(p.getSaleScheme()),
        p.getSgst(),
        p.getCgst());
  }

  private static List<RateView> toViews(List<Rate> rates) {
    if (rates == null) {
      return null;
    }
    return rates.stream()
        .map(r -> new RateView(
            r.getName(),
            r.getPrice(),
            r.getBase(),
            r.getBaseValue(),
            r.getDiscountPercent(),
            toTerms(r.getScheme()),
            r.getManual(),
            r.getGstMode()))
        .toList();
  }

  /** As the rules hand rates back: none (null or empty) is stored as null. */
  private static List<Rate> toRates(List<RateView> rates) {
    if (rates == null || rates.isEmpty()) {
      return null;
    }
    return rates.stream()
        .map(r -> Rate.builder()
            .name(r.name())
            .price(r.price())
            .base(r.base())
            .baseValue(r.baseValue())
            .discountPercent(r.discountPercent())
            .scheme(toScheme(r.scheme()))
            .manual(r.manual())
            .gstMode(r.gstMode())
            .build())
        .toList();
  }

  private static SchemeTerms toTerms(SchemeDto s) {
    return s == null ? null
        : new SchemeTerms(s.getSchemeType(), s.getSchemePayFor(), s.getSchemeFree(),
            s.getSchemePercentage());
  }

  private static SchemeTerms toTerms(Scheme s) {
    return s == null ? null
        : new SchemeTerms(s.getSchemeType(), s.getSchemePayFor(), s.getSchemeFree(),
            s.getSchemePercentage());
  }

  private static SchemeDto toSchemeDto(SchemeTerms s) {
    return s == null ? null
        : new SchemeDto(s.schemeType(), s.schemePayFor(), s.schemeFree(), s.schemePercentage());
  }

  private static Scheme toScheme(SchemeTerms s) {
    return s == null ? null
        : new Scheme(s.schemeType(), s.schemePayFor(), s.schemeFree(), s.schemePercentage());
  }
}
