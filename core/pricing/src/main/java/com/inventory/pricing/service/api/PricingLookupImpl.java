package com.inventory.pricing.service.api;

import com.inventory.pricing.api.PricingLookup;
import com.inventory.pricing.api.PricingView;
import com.inventory.pricing.api.RateView;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.domain.repository.PricingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link PricingLookup} for other modules: the same repository reads they made before, with each
 * stored record copied field for field into the api records.
 */
@Service
@RequiredArgsConstructor
public class PricingLookupImpl implements PricingLookup {

  private final PricingRepository pricingRepository;

  @Override
  public Optional<PricingView> findById(String pricingId) {
    return pricingRepository.findById(pricingId).map(PricingLookupImpl::toView);
  }

  @Override
  public List<PricingView> findAllById(Iterable<String> pricingIds) {
    List<Pricing> found = pricingRepository.findAllById(pricingIds);
    List<PricingView> out = new ArrayList<>(found.size());
    for (Pricing p : found) {
      out.add(toView(p));
    }
    return out;
  }

  @Override
  public List<String> rateNames(String shopId) {
    return pricingRepository.findDistinctRateNamesByShopId(shopId);
  }

  static PricingView toView(Pricing p) {
    if (p == null) {
      return null;
    }
    return new PricingView(
        p.getId(),
        p.getShopId(),
        p.getMaximumRetailPrice(),
        p.getCostPrice(),
        p.getEffectiveCostPrice(),
        p.getCostPriceIncludesTax(),
        p.getPriceToRetail(),
        rates(p.getRates()),
        p.getDefaultRate(),
        p.getSellingPrice(),
        p.getSaleAdditionalDiscount(),
        p.getPurchaseAdditionalDiscount(),
        toTerms(p.getPurchaseScheme()),
        toTerms(p.getSaleScheme()),
        p.getSgst(),
        p.getCgst(),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }

  private static List<RateView> rates(List<Rate> rates) {
    if (rates == null) {
      return null;
    }
    List<RateView> out = new ArrayList<>(rates.size());
    for (Rate r : rates) {
      out.add(r == null ? null : new RateView(
          r.getName(),
          r.getPrice(),
          r.getBase(),
          r.getBaseValue(),
          r.getDiscountPercent(),
          toTerms(r.getScheme()),
          r.getManual(),
          r.getGstMode()));
    }
    return out;
  }

  static SchemeTerms toTerms(Scheme s) {
    return s == null ? null
        : new SchemeTerms(s.getSchemeType(), s.getSchemePayFor(), s.getSchemeFree(),
            s.getSchemePercentage());
  }
}
