package com.inventory.pricing.service.api;

import com.inventory.pricing.api.InventoryPricing;
import com.inventory.pricing.api.LotPricing;
import com.inventory.pricing.api.PricingCreate;
import com.inventory.pricing.api.PricingUpdate;
import com.inventory.pricing.api.RateLine;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.service.InventoryPricingAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link InventoryPricing} for other modules. Each call goes to the same
 * {@link InventoryPricingAdapter} method as before; api records are copied into and out of
 * pricing's own classes field for field, nulls kept as nulls.
 */
@Service
@RequiredArgsConstructor
public class InventoryPricingImpl implements InventoryPricing {

  private final InventoryPricingAdapter adapter;

  @Override
  public Optional<LotPricing> findById(String pricingId) {
    return adapter.findById(pricingId).map(InventoryPricingImpl::toLotPricing);
  }

  @Override
  public Map<String, LotPricing> findByIdIn(List<String> pricingIds) {
    Map<String, PricingReadDto> found = adapter.findByIdIn(pricingIds);
    Map<String, LotPricing> out = new LinkedHashMap<>();
    found.forEach((id, dto) -> out.put(id, toLotPricing(dto)));
    return out;
  }

  @Override
  public String create(PricingCreate command) {
    return adapter.create(toCommand(command));
  }

  @Override
  public void update(String pricingId, PricingUpdate command, String userId) {
    adapter.update(pricingId, toCommand(command), userId);
  }

  public static LotPricing toLotPricing(PricingReadDto d) {
    if (d == null) {
      return null;
    }
    return new LotPricing(
        d.getMaximumRetailPrice(),
        d.getCostPrice(),
        d.getEffectiveCostPrice(),
        d.getPriceToRetail(),
        toRateLines(d.getRates()),
        d.getDefaultRate(),
        d.getSellingPrice(),
        d.getSaleAdditionalDiscount(),
        d.getPurchaseAdditionalDiscount(),
        toTerms(d.getPurchaseScheme()),
        toTerms(d.getSaleScheme()),
        d.getSgst(),
        d.getCgst(),
        d.getCostPriceIncludesTax());
  }

  static PricingCreateCommand toCommand(PricingCreate c) {
    if (c == null) {
      return null;
    }
    return PricingCreateCommand.builder()
        .shopId(c.shopId())
        .verticalId(c.verticalId())
        .maximumRetailPrice(c.maximumRetailPrice())
        .costPrice(c.costPrice())
        .priceToRetail(c.priceToRetail())
        .sellingPrice(c.sellingPrice())
        .rates(toRateDtos(c.rates()))
        .defaultRate(c.defaultRate())
        .saleAdditionalDiscount(c.saleAdditionalDiscount())
        .purchaseAdditionalDiscount(c.purchaseAdditionalDiscount())
        .purchaseScheme(toScheme(c.purchaseScheme()))
        .saleScheme(toScheme(c.saleScheme()))
        .sgst(c.sgst())
        .cgst(c.cgst())
        .costPriceIncludesTax(c.costPriceIncludesTax())
        .build();
  }

  static PricingUpdateCommand toCommand(PricingUpdate u) {
    if (u == null) {
      return null;
    }
    return PricingUpdateCommand.builder()
        .verticalId(u.verticalId())
        .maximumRetailPrice(u.maximumRetailPrice())
        .costPrice(u.costPrice())
        .priceToRetail(u.priceToRetail())
        .sellingPrice(u.sellingPrice())
        .rates(toRateDtos(u.rates()))
        .defaultRate(u.defaultRate())
        .saleAdditionalDiscount(u.saleAdditionalDiscount())
        .purchaseAdditionalDiscount(u.purchaseAdditionalDiscount())
        .purchaseScheme(toScheme(u.purchaseScheme()))
        .saleScheme(toScheme(u.saleScheme()))
        .sgst(u.sgst())
        .cgst(u.cgst())
        .clearFields(u.clearFields())
        .build();
  }

  static List<RateLine> toRateLines(List<RateDto> rates) {
    if (rates == null) {
      return null;
    }
    List<RateLine> out = new ArrayList<>(rates.size());
    for (RateDto r : rates) {
      out.add(r == null ? null : new RateLine(
          r.getName(),
          r.getPrice(),
          r.getBase(),
          r.getBaseValue(),
          r.getDiscountPercent(),
          toTerms(r.getScheme()),
          r.getManual(),
          r.getGstMode(),
          r.getTermsText()));
    }
    return out;
  }

  static List<RateDto> toRateDtos(List<RateLine> rates) {
    if (rates == null) {
      return null;
    }
    List<RateDto> out = new ArrayList<>(rates.size());
    for (RateLine r : rates) {
      out.add(r == null ? null : new RateDto(
          r.name(),
          r.price(),
          r.base(),
          r.baseValue(),
          r.discountPercent(),
          toSchemeDto(r.scheme()),
          r.manual(),
          r.gstMode(),
          r.termsText()));
    }
    return out;
  }

  static SchemeTerms toTerms(SchemeDto s) {
    return s == null ? null
        : new SchemeTerms(s.getSchemeType(), s.getSchemePayFor(), s.getSchemeFree(),
            s.getSchemePercentage());
  }

  static SchemeDto toSchemeDto(SchemeTerms s) {
    return s == null ? null
        : new SchemeDto(s.schemeType(), s.schemePayFor(), s.schemeFree(), s.schemePercentage());
  }

  static Scheme toScheme(SchemeTerms s) {
    return s == null ? null
        : new Scheme(s.schemeType(), s.schemePayFor(), s.schemeFree(), s.schemePercentage());
  }
}
